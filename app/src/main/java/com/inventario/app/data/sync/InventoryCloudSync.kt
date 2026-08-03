package com.inventario.app.data.sync

import android.util.Log
import com.inventario.app.data.dao.AppMetaDao
import com.inventario.app.data.dao.ProductDao
import com.inventario.app.data.dao.SaleRecordDao
import com.inventario.app.data.entity.AppMeta
import com.inventario.app.data.entity.Product
import com.inventario.app.data.entity.SaleRecord
import com.inventario.app.data.order.OrderLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class CloudSyncStatus {
    IDLE,
    SYNCING,
    SYNCED,
    OFFLINE,
    ERROR
}

data class CloudSyncInfo(
    val status: CloudSyncStatus = CloudSyncStatus.IDLE,
    val detail: String? = null
)

class InventoryCloudSync(
    private val config: SyncConfig,
    private val productDao: ProductDao,
    private val appMetaDao: AppMetaDao,
    private val saleRecordDao: SaleRecordDao
) {
    private val _status = MutableStateFlow(CloudSyncInfo())
    val status: StateFlow<CloudSyncInfo> = _status.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private var localInventoryRevision: Long = 0L
    private var applyingRemote = false
    private var networkAvailable = true
    private var pollJob: Job? = null

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                setStatus(CloudSyncStatus.SYNCING)
                pullFullState()
                setStatus(CloudSyncStatus.SYNCED)
            }.onFailure { error ->
                Log.w(TAG, "Sync startup failed", error)
                setStatus(CloudSyncStatus.ERROR, error.toSyncDetail())
            }
        }
        pollJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                if (!networkAvailable) continue
                runCatching { pollForChanges() }.onFailure { error ->
                    Log.w(TAG, "Poll failed", error)
                    setStatus(CloudSyncStatus.ERROR, error.toSyncDetail())
                }
            }
        }
    }

    fun setNetworkAvailable(available: Boolean) {
        networkAvailable = available
        val current = _status.value
        if (!available) {
            if (current.status != CloudSyncStatus.ERROR) {
                setStatus(CloudSyncStatus.OFFLINE, "Sin conexión a internet")
            }
            return
        }
        if (current.status == CloudSyncStatus.OFFLINE) {
            setStatus(CloudSyncStatus.SYNCING, "Reconectando…")
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    suspend fun pushInventoryReplace(products: List<Product>, meta: AppMeta) = withContext(Dispatchers.IO) {
        if (applyingRemote) return@withContext
        setStatus(CloudSyncStatus.SYNCING)
        runCatching {
            val body = JSONObject().apply {
                put("products", productsToJson(products))
                put(
                    "meta",
                    JSONObject().apply {
                        put(FIELD_LAST_INVENTORY_UPDATE, meta.lastInventoryUpdateAt)
                        put(FIELD_BCV_RATE, meta.bcvRate)
                        put(FIELD_BCV_FETCHED_AT, meta.bcvFetchedAt)
                    }
                )
            }
            val response = executeJson(
                Request.Builder()
                    .url(endpoint("/v1/inventory"))
                    .put(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .withAuth()
                    .build()
            )
            localInventoryRevision = response.optLong(FIELD_INVENTORY_REVISION, System.currentTimeMillis())
            setStatus(CloudSyncStatus.SYNCED)
        }.onFailure { error ->
            Log.w(TAG, "pushInventoryReplace failed", error)
            setStatus(CloudSyncStatus.ERROR, error.toSyncDetail())
            throw error
        }
    }

    suspend fun pushStockDeductions(
        lines: List<OrderLine>,
        productsById: Map<Long, Product>
    ) = withContext(Dispatchers.IO) {
        if (applyingRemote) return@withContext
        runCatching {
            val linesJson = JSONArray()
            for (line in lines) {
                val product = productsById[line.productId]
                    ?: error("Producto no encontrado para sincronizar: ${line.description}")
                val syncId = product.syncId.takeIf { it.isNotBlank() }
                    ?: error("Producto sin identificador de nube: ${line.description}")
                linesJson.put(
                    JSONObject().apply {
                        put(FIELD_SYNC_ID, syncId)
                        put(FIELD_QUANTITY, line.quantity)
                    }
                )
            }
            val body = JSONObject().put("lines", linesJson)
            executeJson(
                Request.Builder()
                    .url(endpoint("/v1/inventory/deduct"))
                    .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .withAuth()
                    .build()
            )
        }.onFailure { error ->
            Log.w(TAG, "pushStockDeductions failed", error)
            setStatus(CloudSyncStatus.ERROR, error.toSyncDetail())
            throw error
        }
    }

    suspend fun pushMeta(meta: AppMeta) = withContext(Dispatchers.IO) {
        if (applyingRemote) return@withContext
        runCatching {
            val body = JSONObject().apply {
                put(FIELD_BCV_RATE, meta.bcvRate)
                put(FIELD_BCV_FETCHED_AT, meta.bcvFetchedAt)
                put(FIELD_LAST_INVENTORY_UPDATE, meta.lastInventoryUpdateAt)
                put(FIELD_INVENTORY_REVISION, localInventoryRevision)
            }
            executeJson(
                Request.Builder()
                    .url(endpoint("/v1/meta"))
                    .put(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .withAuth()
                    .build()
            )
        }
    }

    suspend fun pushSale(record: SaleRecord) = withContext(Dispatchers.IO) {
        if (applyingRemote || record.syncId.isBlank()) return@withContext
        runCatching {
            val body = JSONObject().apply {
                put(FIELD_SYNC_ID, record.syncId)
                put(FIELD_CREATED_AT, record.createdAt)
                put(FIELD_TOTAL_USD, record.totalUsd)
            }
            executeJson(
                Request.Builder()
                    .url(endpoint("/v1/sales"))
                    .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .withAuth()
                    .build()
            )
        }
    }

    private suspend fun pollForChanges() = withContext(Dispatchers.IO) {
        if (applyingRemote) return@withContext
        val state = fetchState()
        val remoteRevision = state.optLong(FIELD_INVENTORY_REVISION, 0L)
        if (remoteRevision > localInventoryRevision) {
            applyRemoteState(state)
            setStatus(CloudSyncStatus.SYNCED)
        }
    }

    private suspend fun pullFullState() = withContext(Dispatchers.IO) {
        val state = fetchState()
        applyRemoteState(state)
    }

    private suspend fun fetchState(): JSONObject = withContext(Dispatchers.IO) {
        executeJson(
            Request.Builder()
                .url(endpoint("/v1/state"))
                .get()
                .withAuth()
                .build()
        )
    }

    private suspend fun applyRemoteState(state: JSONObject) {
        applyingRemote = true
        try {
            localInventoryRevision = state.optLong(FIELD_INVENTORY_REVISION, 0L)

            val metaJson = state.optJSONObject("meta")
            if (metaJson != null) {
                val meta = appMetaDao.get() ?: AppMeta()
                appMetaDao.upsert(
                    meta.copy(
                        bcvRate = metaJson.optDoubleOrNull(FIELD_BCV_RATE) ?: meta.bcvRate,
                        bcvFetchedAt = metaJson.optLongOrNull(FIELD_BCV_FETCHED_AT) ?: meta.bcvFetchedAt,
                        lastInventoryUpdateAt = metaJson.optLongOrNull(FIELD_LAST_INVENTORY_UPDATE)
                            ?: meta.lastInventoryUpdateAt
                    )
                )
            }

            val productsArray = state.optJSONArray("products")
            if (productsArray != null) {
                val products = productsArray.toProductList()
                if (products.isEmpty()) {
                    productDao.clearAll()
                    productDao.rebuildFtsIndex()
                } else {
                    val syncIds = products.map { it.syncId }
                    productDao.replaceAllFromCloud(products, syncIds)
                }
            }

            val salesArray = state.optJSONArray("sales")
            if (salesArray != null) {
                for (i in 0 until salesArray.length()) {
                    val doc = salesArray.optJSONObject(i) ?: continue
                    val syncId = doc.optString(FIELD_SYNC_ID).takeIf { it.isNotBlank() } ?: continue
                    if (saleRecordDao.countBySyncId(syncId) > 0) continue
                    val createdAt = doc.optLong(FIELD_CREATED_AT, 0L)
                    val totalUsd = doc.optDouble(FIELD_TOTAL_USD, Double.NaN)
                    if (createdAt <= 0L || totalUsd.isNaN()) continue
                    saleRecordDao.insert(
                        SaleRecord(
                            syncId = syncId,
                            createdAt = createdAt,
                            totalUsd = totalUsd
                        )
                    )
                }
            }
        } finally {
            applyingRemote = false
        }
    }

    private fun executeJson(request: Request): JSONObject {
        client.newCall(request).execute().use { response ->
            val bodyText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val serverMessage = runCatching {
                    JSONObject(bodyText).optString("error")
                }.getOrNull()?.takeIf { it.isNotBlank() }
                throw SyncHttpException(
                    response.code,
                    serverMessage ?: "HTTP ${response.code}"
                )
            }
            if (bodyText.isBlank()) return JSONObject()
            return JSONObject(bodyText)
        }
    }

    private fun endpoint(path: String): String {
        val base = config.baseUrl.trimEnd('/')
        return "$base$path"
    }

    private fun Request.Builder.withAuth(): Request.Builder {
        if (config.apiKey.isNotBlank()) {
            addHeader("X-Api-Key", config.apiKey)
        }
        return this
    }

    private fun productsToJson(products: List<Product>): JSONArray {
        val array = JSONArray()
        for (product in products) {
            array.put(
                JSONObject().apply {
                    put(FIELD_SYNC_ID, product.syncId)
                    put(FIELD_DESCRIPTION, product.description)
                    put(FIELD_QUANTITY, product.quantity)
                    put(FIELD_UNIT, product.unit)
                    put(FIELD_PRICE, product.price)
                    put(FIELD_UPDATED_AT, product.updatedAt)
                }
            )
        }
        return array
    }

    private fun JSONArray.toProductList(): List<Product> {
        val products = mutableListOf<Product>()
        for (i in 0 until length()) {
            val json = optJSONObject(i) ?: continue
            val syncId = json.optString(FIELD_SYNC_ID).takeIf { it.isNotBlank() } ?: continue
            val description = json.optString(FIELD_DESCRIPTION).takeIf { it.isNotBlank() } ?: continue
            val quantity = json.optDouble(FIELD_QUANTITY, Double.NaN)
            if (quantity.isNaN()) continue
            val unit = json.optString(FIELD_UNIT, "UNIDAD")
            val price = json.optDouble(FIELD_PRICE, Double.NaN)
            if (price.isNaN()) continue
            val updatedAt = json.optLong(FIELD_UPDATED_AT, System.currentTimeMillis())
            products.add(
                Product(
                    syncId = syncId,
                    description = description,
                    quantity = quantity,
                    unit = unit,
                    price = price,
                    updatedAt = updatedAt
                )
            )
        }
        return products
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        return optDouble(key)
    }

    private fun JSONObject.optLongOrNull(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        return optLong(key)
    }

    private fun setStatus(status: CloudSyncStatus, detail: String? = null) {
        _status.value = CloudSyncInfo(status = status, detail = detail)
    }

    private fun Throwable.toSyncDetail(): String = when (this) {
        is SyncHttpException -> when (code) {
            401 -> "Clave API inválida. Revisa la configuración de sincronización."
            403 -> "Acceso denegado al servidor de sincronización."
            404 -> "Servidor no encontrado. Verifica la URL del servidor."
            in 500..599 -> "Error del servidor de sincronización (HTTP $code)."
            else -> message ?: "Error HTTP $code"
        }
        else -> when {
            message?.contains("network", ignoreCase = true) == true ||
                message?.contains("failed to connect", ignoreCase = true) == true ||
                message?.contains("Unable to resolve host", ignoreCase = true) == true ->
                "Sin conexión a internet o servidor inaccesible."
            else -> localizedMessage ?: "Error de sincronización"
        }
    }

    private class SyncHttpException(val code: Int, message: String) : Exception(message)

    companion object {
        private const val TAG = "InventoryCloudSync"
        private const val POLL_INTERVAL_MS = 15_000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private const val FIELD_SYNC_ID = "syncId"
        private const val FIELD_DESCRIPTION = "description"
        private const val FIELD_QUANTITY = "quantity"
        private const val FIELD_UNIT = "unit"
        private const val FIELD_PRICE = "price"
        private const val FIELD_UPDATED_AT = "updatedAt"
        private const val FIELD_INVENTORY_REVISION = "inventoryRevision"
        private const val FIELD_LAST_INVENTORY_UPDATE = "lastInventoryUpdateAt"
        private const val FIELD_BCV_RATE = "bcvRate"
        private const val FIELD_BCV_FETCHED_AT = "bcvFetchedAt"
        private const val FIELD_CREATED_AT = "createdAt"
        private const val FIELD_TOTAL_USD = "totalUsd"

        fun newSyncId(): String = UUID.randomUUID().toString()
    }
}
