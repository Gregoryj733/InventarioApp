package com.inventario.app.data.repository

import android.content.Context
import android.net.Uri
import com.inventario.app.data.entity.AppMeta
import com.inventario.app.data.entity.CashClosingAlertType
import com.inventario.app.data.entity.CashClosingRecord
import com.inventario.app.data.entity.CashClosingStatus
import com.inventario.app.data.entity.Product
import com.inventario.app.data.entity.SaleRecord
import com.inventario.app.data.entity.User
import com.inventario.app.data.entity.UserRole
import com.inventario.app.data.excel.ImportResult
import com.inventario.app.data.order.OrderLine
import com.inventario.app.data.search.ProductSearch
import com.inventario.app.data.session.SessionManager
import com.inventario.app.data.sync.ApiException
import com.inventario.app.data.sync.CloudEvent
import com.inventario.app.data.sync.CloudSync
import com.inventario.app.data.sync.CloudSyncInfo
import com.inventario.app.data.sync.CloudSyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

enum class LoginStatus {
    SUCCESS,
    INVALID,
    INACTIVE
}

/**
 * Autenticación exclusivamente contra el sync-server: no hay usuarios ni
 * contraseñas almacenados en el dispositivo. El servidor mantiene y valida
 * las credenciales (bcrypt) y emite un token de sesión (JWT).
 */
class AuthRepository(
    private var cloudSync: CloudSync,
    private val sessionManager: SessionManager
) {
    fun setCloudSync(sync: CloudSync) {
        cloudSync = sync
    }

    /** El servidor crea los usuarios por defecto (admin/admin, consulta/consulta) al iniciar. */
    suspend fun ensureDefaultUsers() = Unit

    suspend fun login(username: String, password: String): User? = withContext(Dispatchers.IO) {
        runCatching {
            val response = cloudSync.postJson(
                "/v1/auth/login",
                JSONObject().apply {
                    put("username", username.trim())
                    put("password", password)
                }
            )
            val token = response.getString("token")
            cloudSync.setAuthToken(token)
            sessionManager.saveToken(token)
            response.getJSONObject("user").toUser()
        }.getOrNull()
    }

    suspend fun loginStatus(username: String, password: String): LoginStatus = withContext(Dispatchers.IO) {
        runCatching {
            cloudSync.postJson(
                "/v1/auth/login",
                JSONObject().apply {
                    put("username", username.trim())
                    put("password", password)
                }
            )
            LoginStatus.SUCCESS
        }.getOrElse { error ->
            if (error is ApiException && error.code == 403) LoginStatus.INACTIVE else LoginStatus.INVALID
        }
    }

    /** Devuelve las cuentas administrables desde el módulo de Usuarios (Supervisor y Consulta). */
    suspend fun listManagedUsers(): List<User> = withContext(Dispatchers.IO) {
        cloudSync.get("/v1/users").optJSONArray("users")?.toUserList() ?: emptyList()
    }

    suspend fun createManagedUser(
        username: String,
        password: String,
        sucursal: String,
        role: UserRole
    ): Result<User> =
        withContext(Dispatchers.IO) {
            runCatching {
                cloudSync.postJson(
                    "/v1/users",
                    JSONObject().apply {
                        put("username", username.trim())
                        put("password", password)
                        put("sucursal", sucursal.trim())
                        put("role", role.name)
                    }
                ).toUser()
            }
        }

    suspend fun deleteManagedUser(id: Long): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { cloudSync.delete("/v1/users/$id"); Unit }
    }

    suspend fun setManagedUserActive(id: Long, active: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            cloudSync.patchJson("/v1/users/$id", JSONObject().put("active", active))
            Unit
        }
    }

    suspend fun assignManagedUserSucursal(id: Long, sucursal: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val branch = sucursal.trim()
            require(branch.isNotBlank()) { "Indica la sucursal del usuario." }
            cloudSync.patchJson("/v1/users/$id", JSONObject().put("sucursal", branch))
            Unit
        }
    }
}

/**
 * Inventario, ventas y cierres de caja: todo se lee y escribe contra el
 * sync-server. Se mantiene una caché en memoria de productos/meta que se
 * refresca al iniciar y ante cada evento de WebSocket ("inventory"), de modo
 * que la búsqueda y el conteo de stock sean instantáneos sin repetir
 * peticiones de red por cada tecla escrita.
 */
class InventoryRepository(
    private val context: Context,
    private var cloudSync: CloudSync,
    private val appScope: CoroutineScope
) {
    private val productsFlow = MutableStateFlow<List<Product>>(emptyList())
    private val metaFlow = MutableStateFlow<AppMeta?>(null)
    private val pendingOrders = ArrayDeque<PendingOrder>()

    init {
        appScope.launch { refreshInventoryState() }
        appScope.launch {
            cloudSync.events.collect { event ->
                if (event is CloudEvent.Inventory) refreshInventoryState()
            }
        }
        appScope.launch {
            cloudSync.status.collect { info ->
                if (info.status == CloudSyncStatus.SYNCED && pendingOrders.isNotEmpty()) {
                    flushPendingOrders()
                }
            }
        }
    }

    fun setCloudSync(sync: CloudSync) {
        cloudSync = sync
        appScope.launch { refreshInventoryState() }
    }

    fun observeAllProducts(): StateFlow<List<Product>> = productsFlow.asStateFlow()

    fun observeMeta(): StateFlow<AppMeta?> = metaFlow.asStateFlow()

    fun observeCloudSyncStatus(): StateFlow<CloudSyncInfo> = cloudSync.status

    suspend fun search(query: String): List<Product> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        val tokens = ProductSearch.tokenize(q)
        val primaryToken = tokens.firstOrNull() ?: return@withContext emptyList()
        productsFlow.value
            .asSequence()
            .filter { ProductSearch.matchesAllTokens(it.description, q) }
            .sortedWith(
                compareBy(
                    { product -> if (product.description.startsWith(primaryToken, ignoreCase = true)) 0 else 1 },
                    { product -> product.description }
                )
            )
            .take(40)
            .toList()
    }

    suspend fun suggestions(query: String): List<String> =
        search(query).map { it.description }.distinct().take(12)

    suspend fun replaceInventoryFromExcel(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return@withContext ImportResult(0, 0, listOf("No se pudo abrir el archivo."))
        runCatching {
            val response = cloudSync.postMultipart("/v1/inventory/import", "inventario.xlsx", bytes)
            val errors = response.optJSONArray("errors")?.toStringList() ?: emptyList()
            ImportResult(
                imported = response.optInt("imported", 0),
                skipped = response.optInt("skipped", 0),
                errors = errors
            )
        }.onSuccess {
            refreshInventoryState()
        }.getOrElse { error ->
            ImportResult(0, 0, listOf(error.message ?: "No se pudo importar el archivo."))
        }
    }

    suspend fun productCount(): Int = productsFlow.value.size

    suspend fun findProduct(id: Long): Product? = productsFlow.value.find { it.id == id }

    suspend fun saveBcvRate(rate: Double) = withContext(Dispatchers.IO) {
        runCatching {
            val response = cloudSync.putJson(
                "/v1/meta",
                JSONObject().apply {
                    put("bcvRate", rate)
                    put("bcvFetchedAt", System.currentTimeMillis())
                }
            )
            metaFlow.update { current ->
                (current ?: AppMeta()).copy(
                    bcvRate = response.optDoubleOrNull("bcvRate") ?: rate,
                    bcvFetchedAt = response.optLongOrNull("bcvFetchedAt")
                )
            }
        }
    }

    suspend fun currentBcvRate(): Double? = metaFlow.value?.bcvRate

    /**
     * Descuenta stock y registra la venta directamente en el servidor. Si el
     * servidor no es alcanzable (sin internet, cold start, etc.) el pedido se
     * confirma localmente con descuento optimista y se reintenta en cuanto la
     * conexión en tiempo real vuelva a sincronizarse.
     */
    suspend fun executeOrder(lines: List<OrderLine>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val now = System.currentTimeMillis()
            val currentProducts = productsFlow.value
            val syncIdsByProductId = lines.associate { line ->
                val product = currentProducts.find { it.id == line.productId }
                    ?: error("Producto no encontrado: ${line.description}")
                if (product.quantity < line.quantity) {
                    error(
                        "Stock insuficiente para \"${line.description}\" " +
                            "(disponible ${product.quantity}, pedido ${line.quantity})."
                    )
                }
                line.productId to product.syncId
            }
            val totalUsd = lines.sumOf { it.totalUsd }
            val rate = metaFlow.value?.bcvRate ?: 0.0
            val sale = SaleRecord(
                syncId = CloudSync.newSyncId(),
                createdAt = now,
                totalUsd = totalUsd,
                bcvRate = rate
            )
            val pending = PendingOrder(lines, sale, syncIdsByProductId)

            runCatching { pushOrder(pending) }
                .onSuccess { applyLocalDeduction(lines, now) }
                .onFailure { error ->
                    if (error.isConnectivityIssue()) {
                        applyLocalDeduction(lines, now)
                        pendingOrders.addLast(pending)
                    } else {
                        throw error
                    }
                }
            Unit
        }
    }

    suspend fun totalSalesToday(): Double = withContext(Dispatchers.IO) {
        val (start, end) = todayBounds()
        fetchSales().filter { it.createdAt in start until end }.sumOf { it.totalUsd }
    }

    suspend fun confirmedOrdersToday(): Int = withContext(Dispatchers.IO) {
        val (start, end) = todayBounds()
        fetchSales().count { it.createdAt in start until end }
    }

    suspend fun resetTodayOrders() = withContext(Dispatchers.IO) {
        val (start, end) = todayBounds()
        cloudSync.delete("/v1/sales", mapOf("start" to start.toString(), "end" to end.toString()))
    }

    suspend fun saveCashClosing(record: CashClosingRecord): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val body = record.toJsonBody().apply { put("username", record.username.trim().lowercase()) }
            cloudSync.postJson("/v1/cash-closings", body).optLong("id", record.id)
        }
    }

    suspend fun cashClosingAlertForUser(username: String): CashClosingAlertType? = withContext(Dispatchers.IO) {
        val latest = latestClosingToday(username)
        when (latest?.status) {
            CashClosingStatus.REJECTED -> CashClosingAlertType.REJECTED_RESUBMIT
            CashClosingStatus.APPROVED -> CashClosingAlertType.APPROVED_SUCCESS
            else -> null
        }
    }

    suspend fun hasPendingClosings(): Boolean = withContext(Dispatchers.IO) {
        val (dayStart, dayEnd) = todayBounds()
        fetchCashClosings().any { it.status == CashClosingStatus.PENDING && it.closedAt in dayStart until dayEnd }
    }

    suspend fun latestClosingToday(username: String): CashClosingRecord? = withContext(Dispatchers.IO) {
        val normalized = username.trim().lowercase()
        val (dayStart, dayEnd) = todayBounds()
        fetchCashClosings()
            .filter { it.username == normalized && it.closedAt in dayStart until dayEnd }
            .maxByOrNull { it.closedAt }
    }

    suspend fun maxRevisionToday(username: String): Int = withContext(Dispatchers.IO) {
        val normalized = username.trim().lowercase()
        val (dayStart, dayEnd) = todayBounds()
        fetchCashClosings()
            .filter { it.username == normalized && it.closedAt in dayStart until dayEnd }
            .maxOfOrNull { it.revisionNumber } ?: 0
    }

    /**
     * Descuenta stock y registra la venta en una sola llamada atómica e
     * idempotente (por syncId) en el servidor: un reintento tras un corte de
     * red nunca descuenta el stock dos veces ni deja una venta a medio
     * registrar (antes eran dos requests independientes: /inventory/deduct
     * y /sales).
     */
    private suspend fun pushOrder(pending: PendingOrder) {
        val orderLines = JSONArray()
        pending.lines.forEach { line ->
            val syncId = pending.productSyncIds[line.productId]
                ?: error("Producto sin identificador de nube: ${line.description}")
            orderLines.put(
                JSONObject().apply {
                    put("productSyncId", syncId)
                    put("description", line.description)
                    put("quantity", line.quantity)
                    put("unit", line.unit)
                    put("unitPriceUsd", line.unitPriceUsd)
                    put("totalUsd", line.totalUsd)
                }
            )
        }
        cloudSync.postJson(
            "/v1/orders",
            JSONObject().apply {
                put("syncId", pending.sale.syncId)
                put("createdAt", pending.sale.createdAt)
                put("totalUsd", pending.sale.totalUsd)
                put("bcvRate", pending.sale.bcvRate)
                put("lines", orderLines)
            }
        )
    }

    private suspend fun flushPendingOrders() {
        while (pendingOrders.isNotEmpty()) {
            val pending = pendingOrders.first()
            val result = runCatching { pushOrder(pending) }
            if (result.isSuccess) {
                pendingOrders.removeFirst()
            } else {
                if (result.exceptionOrNull()?.isConnectivityIssue() != true) {
                    pendingOrders.removeFirst()
                }
                return
            }
        }
        refreshInventoryState()
    }

    private fun applyLocalDeduction(lines: List<OrderLine>, now: Long) {
        productsFlow.update { products ->
            products.map { product ->
                val line = lines.find { it.productId == product.id }
                if (line != null) product.copy(quantity = product.quantity - line.quantity, updatedAt = now) else product
            }
        }
    }

    private suspend fun refreshInventoryState() {
        runCatching {
            val state = cloudSync.get("/v1/state")
            val metaJson = state.optJSONObject("meta")
            metaFlow.value = AppMeta(
                bcvRate = metaJson?.optDoubleOrNull("bcvRate"),
                bcvFetchedAt = metaJson?.optLongOrNull("bcvFetchedAt"),
                lastInventoryUpdateAt = metaJson?.optLongOrNull("lastInventoryUpdateAt")
            )
            productsFlow.value = (state.optJSONArray("products") ?: JSONArray()).toProductList()
        }
    }

    private suspend fun fetchSales(): List<SaleRecord> = runCatching {
        cloudSync.get("/v1/sales").optJSONArray("sales")?.toSaleList() ?: emptyList()
    }.getOrDefault(emptyList())

    private suspend fun fetchCashClosings(): List<CashClosingRecord> = runCatching {
        cloudSync.get("/v1/cash-closings").optJSONArray("cashClosings")?.toCashClosingList() ?: emptyList()
    }.getOrDefault(emptyList())

    // /v1/orders es idempotente (por syncId), así que cualquier error 5xx del
    // servidor de sincronización es seguro de reintentar en cuanto vuelva la
    // conexión, sin riesgo de descontar stock dos veces.
    private fun Throwable.isConnectivityIssue(): Boolean =
        this is ApiException && (code == 0 || code in 500..599) ||
            this !is ApiException

    private fun todayBounds(): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        cal.add(Calendar.DAY_OF_MONTH, 1)
        val end = cal.timeInMillis
        return start to end
    }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { optString(it, null) }

    private data class PendingOrder(
        val lines: List<OrderLine>,
        val sale: SaleRecord,
        val productSyncIds: Map<Long, String>
    )

    companion object {
        const val MAX_CLOSINGS_PER_DAY = 5
    }
}
