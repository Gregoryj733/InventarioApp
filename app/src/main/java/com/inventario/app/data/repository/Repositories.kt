package com.inventario.app.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.inventario.app.data.branch.BranchCatalog
import com.inventario.app.data.branch.normalizeBranchId
import com.inventario.app.data.catalog.findProductInCatalog
import com.inventario.app.data.catalog.inventoryCatalogChanged
import com.inventario.app.data.entity.AppMeta
import com.inventario.app.data.entity.stableProductId
import com.inventario.app.data.entity.CashClosingAlertType
import com.inventario.app.data.entity.CashClosingRecord
import com.inventario.app.data.entity.CashClosingStatus
import com.inventario.app.data.cashea.CasheaCalculator
import com.inventario.app.data.entity.ConfirmedOrderPreview
import com.inventario.app.data.entity.DiscountTicket
import com.inventario.app.data.entity.Product
import com.inventario.app.data.entity.SaleLineItem
import com.inventario.app.data.entity.SaleRecord
import com.inventario.app.data.entity.User
import com.inventario.app.data.entity.UserRole
import com.inventario.app.data.excel.ImportResult
import com.inventario.app.data.order.OrderLine
import com.inventario.app.data.order.matchesProduct
import com.inventario.app.data.order.toSaleLineItem
import com.inventario.app.data.search.ProductSearch
import com.inventario.app.data.session.SessionManager
import com.inventario.app.data.sync.ApiException
import com.inventario.app.data.sync.CloudEvent
import com.inventario.app.data.sync.CloudSync
import com.inventario.app.data.sync.CloudSyncInfo
import com.inventario.app.data.sync.CloudSyncStatus
import com.inventario.app.data.sync.SyncConfig
import com.inventario.app.data.sync.toUserMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId
import java.time.ZonedDateTime

sealed class LoginResult {
    data class Success(val user: User, val token: String) : LoginResult()
    data object InvalidCredentials : LoginResult()
    data object Inactive : LoginResult()
    data class Unavailable(val message: String) : LoginResult()
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

    /**
     * Despierta el sync-server si hace falta y autentica en una sola pasada.
     * Distingue credenciales inválidas de servidor caído / cold start.
     */
    suspend fun login(username: String, password: String): LoginResult = withContext(Dispatchers.IO) {
        try {
            cloudSync.ensureServerReadyForLogin()
        } catch (error: Throwable) {
            return@withContext LoginResult.Unavailable(
                error.toUserMessage(
                    "No se pudo contactar al servidor de sincronización. " +
                        "En plan gratuito puede tardar hasta 1 minuto en iniciar; inténtalo de nuevo."
                )
            )
        }

        try {
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
            LoginResult.Success(response.getJSONObject("user").toUser(), token)
        } catch (error: ApiException) {
            when (error.code) {
                403 -> LoginResult.Inactive
                401 -> LoginResult.InvalidCredentials
                else -> LoginResult.Unavailable(
                    error.toUserMessage("No se pudo iniciar sesión. Inténtalo de nuevo.")
                )
            }
        } catch (error: Throwable) {
            LoginResult.Unavailable(
                error.toUserMessage("No se pudo iniciar sesión. Inténtalo de nuevo.")
            )
        }
    }

    /**
     * Autentica contra una instancia sync-server sin tocar el [cloudSync] activo
     * ni el token de la sucursal en uso (prefetch multi-sucursal, reauth al cambiar).
     */
    suspend fun loginOnBranch(
        config: SyncConfig,
        username: String,
        password: String
    ): LoginResult = withContext(Dispatchers.IO) {
        val tempSync = CloudSync(config)
        try {
            tempSync.ensureServerReadyForLogin()
        } catch (error: Throwable) {
            return@withContext LoginResult.Unavailable(
                error.toUserMessage(
                    "No se pudo contactar al servidor de sincronización. " +
                        "En plan gratuito puede tardar hasta 1 minuto en iniciar; inténtalo de nuevo."
                )
            )
        }

        try {
            val response = tempSync.postJson(
                "/v1/auth/login",
                JSONObject().apply {
                    put("username", username.trim())
                    put("password", password)
                }
            )
            val token = response.getString("token")
            LoginResult.Success(response.getJSONObject("user").toUser(), token)
        } catch (error: ApiException) {
            when (error.code) {
                403 -> LoginResult.Inactive
                401 -> LoginResult.InvalidCredentials
                else -> LoginResult.Unavailable(
                    error.toUserMessage("No se pudo iniciar sesión. Inténtalo de nuevo.")
                )
            }
        } catch (error: Throwable) {
            LoginResult.Unavailable(
                error.toUserMessage("No se pudo iniciar sesión. Inténtalo de nuevo.")
            )
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

    /**
     * Corrige el perfil de una cuenta ya creada (p. ej. si quedó como
     * Consulta por error y debía ser Supervisor) sin tener que eliminarla y
     * volver a crearla.
     */
    suspend fun updateManagedUserRole(id: Long, role: UserRole): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            cloudSync.patchJson("/v1/users/$id", JSONObject().put("role", role.name))
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
 * sync-server. El inventario visible se mantiene en memoria y en disco por
 * sucursal. Solo se reemplaza por completo al importar Excel (local o vía
 * notificación de sucursal) o en el primer arranque sin caché. Los pedidos
 * ajustan cantidades localmente sin volver a descargar el catálogo entero.
 */
class InventoryRepository(
    private val context: Context,
    private var cloudSync: CloudSync,
    private val appScope: CoroutineScope
) {
    private val productsFlow = MutableStateFlow<List<Product>>(emptyList())
    private val metaFlow = MutableStateFlow<AppMeta?>(null)
    private val confirmedOrdersTodayFlow = MutableStateFlow<List<ConfirmedOrderPreview>>(emptyList())
    private val pendingOrders = ArrayDeque<PendingOrder>()
    private var orderPreviewBranchId: String? = cloudSync.branchId
    private var cachedLocalOrderPreviews: List<ConfirmedOrderPreview>? = null
    private var cachedLocalOrderDayKey: String? = null
    // Reenvío estable: al reconectar (p. ej. tras login) se sustituye
    // cloudSync pero las pantallas siguen suscritas al mismo SharedFlow/StateFlow.
    private val cloudEventsRelay = MutableSharedFlow<CloudEvent>(extraBufferCapacity = 32)
    private val cloudStatusRelay = MutableStateFlow(cloudSync.status.value)
    private var cloudEventsJob: Job? = null
    private var cloudStatusJob: Job? = null
    private var confirmedOrdersPollJob: Job? = null
    private var inventoryPollJob: Job? = null
    private var cashClosingsPollJob: Job? = null
    private var metaPollJob: Job? = null
    private var lastSyncedOrdersRefreshAt = 0L
    private var lastBranchOrdersRefreshAt = 0L
    private var lastBranchInventoryRefreshAt = 0L
    private var lastBranchCashClosingsRefreshAt = 0L
    private var lastBranchMetaRefreshAt = 0L
    private var cachedLastInventoryUpdateAt: Long = 0L
    private var cachedInventoryRevision: Long = 0L
    private var cachedLastSalesUpdateAt: Long = 0L
    private var cachedLastOrdersResetAt: Long = 0L
    /** Último reinicio de pedidos ya aplicado en este dispositivo (sucursal activa). */
    private var acknowledgedOrdersResetAt: Long = 0L
    /** Tras un reinicio local, ignora lecturas obsoletas del servidor que aún traen pedidos. */
    private var ordersResetGraceUntil: Long = 0L
    private var cachedCashClosingsSignature: String? = null
    private var cachedMetaSyncSignature: String? = null
    /** Evita que un refresh del servidor pise un descuento local recién aplicado. */
    private var protectLocalInventoryUntil: Long = 0L

    init {
        restoreProductsFromDisk()
        restoreMetaFromDisk()
        restoreConfirmedOrdersFromDisk()
        appScope.launch {
            refreshInventoryState()
        }
        appScope.launch { refreshConfirmedOrdersFlow() }
        attachCloudSyncListeners(cloudSync)
        startConfirmedOrdersPolling()
        startInventoryPolling()
        startCashClosingsPolling()
        startMetaPolling()
    }

    fun setCloudSync(sync: CloudSync) {
        val previousBranchId = orderPreviewBranchId
        val branchChanged = previousBranchId != null &&
            normalizeBranchId(previousBranchId) != normalizeBranchId(sync.branchId)
        cloudSync = sync
        orderPreviewBranchId = sync.branchId
        attachCloudSyncListeners(sync)
        if (branchChanged) {
            resetInMemoryForBranchSwitch()
            restoreProductsFromDisk()
            restoreMetaFromDisk(force = true)
            restoreConfirmedOrdersFromDisk()
            appScope.launch {
                refreshMetaFromBranchEvent(force = true)
                refreshInventoryState(force = true)
                refreshConfirmedOrdersFromBranchEvent(force = true)
                refreshCashClosingsFromBranchEvent(force = true)
            }
        } else {
            invalidateLocalOrderPreviewCache()
            appScope.launch { refreshConfirmedOrdersFlow() }
        }
    }

    /**
     * Limpia estado en memoria al cambiar de sucursal activa. Evita que
     * inventario, pedidos o meta de una instancia se muestren en otra.
     */
    private fun resetInMemoryForBranchSwitch() {
        pendingOrders.clear()
        productsFlow.value = emptyList()
        metaFlow.value = null
        confirmedOrdersTodayFlow.value = emptyList()
        invalidateLocalOrderPreviewCache()
        cachedLastOrdersResetAt = 0L
        acknowledgedOrdersResetAt = 0L
        ordersResetGraceUntil = 0L
        cachedLastSalesUpdateAt = 0L
        cachedLastInventoryUpdateAt = 0L
        cachedInventoryRevision = 0L
        cachedCashClosingsSignature = null
        cachedMetaSyncSignature = null
        protectLocalInventoryUntil = 0L
        lastBranchOrdersRefreshAt = 0L
        lastBranchInventoryRefreshAt = 0L
        lastBranchCashClosingsRefreshAt = 0L
        lastBranchMetaRefreshAt = 0L
    }

    /**
     * Tras [setCloudSync] o [InventarioApplication.restartCloudSync] la instancia
     * anterior deja de emitir eventos WebSocket; re-suscribir aquí garantiza que
     * todos los dispositivos de la sucursal refresquen pedidos e inventario en vivo.
     */
    private fun attachCloudSyncListeners(sync: CloudSync) {
        cloudEventsJob?.cancel()
        cloudStatusJob?.cancel()
        cloudStatusRelay.value = sync.status.value
        var previousStatus = sync.status.value.status
        cloudEventsJob = appScope.launch(Dispatchers.IO) {
            sync.events.collect { event ->
                cloudEventsRelay.emit(event)
                when (event) {
                    is CloudEvent.Inventory -> appScope.launch(Dispatchers.IO) {
                        // La tasa BCV se guarda vía PUT /v1/meta (mismo broadcast
                        // `inventory`); refrescar meta sin el debounce del catálogo.
                        refreshMetaFromBranchEvent(force = true)
                        refreshInventoryFromBranchEvent()
                    }
                    is CloudEvent.Sales -> appScope.launch(Dispatchers.IO) {
                        refreshConfirmedOrdersFromBranchEvent()
                        // Respaldo: stock actualizado en el mismo POST /v1/orders.
                        refreshInventoryState(fromBranchEvent = true)
                    }
                    is CloudEvent.CashClosings -> appScope.launch(Dispatchers.IO) {
                        refreshCashClosingsFromBranchEvent(force = true)
                    }
                    else -> Unit
                }
            }
        }
        cloudStatusJob = appScope.launch(Dispatchers.IO) {
            sync.status.collect { info ->
                cloudStatusRelay.value = info
                val becameSynced = previousStatus != CloudSyncStatus.SYNCED &&
                    info.status == CloudSyncStatus.SYNCED
                previousStatus = info.status
                if (becameSynced) {
                    refreshConfirmedOrdersIfDue()
                    refreshInventoryState(force = true)
                    appScope.launch { refreshMetaFromBranchEvent(force = true) }
                    appScope.launch { refreshCashClosingsFromBranchEvent(force = true) }
                }
                if (info.status == CloudSyncStatus.SYNCED && pendingOrders.isNotEmpty()) {
                    flushPendingOrders()
                }
            }
        }
    }

    /** Respaldo si el WebSocket no entrega eventos de inventario (red móvil, Render free). */
    private fun startInventoryPolling() {
        inventoryPollJob?.cancel()
        inventoryPollJob = appScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(INVENTORY_POLL_INTERVAL_MS)
                if (cloudStatusRelay.value.status != CloudSyncStatus.OFFLINE) {
                    refreshInventoryState()
                }
            }
        }
    }

    /** Respaldo si el WebSocket no entrega eventos (red móvil, Render free, etc.). */
    private fun startConfirmedOrdersPolling() {
        confirmedOrdersPollJob?.cancel()
        confirmedOrdersPollJob = appScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(CONFIRMED_ORDERS_POLL_INTERVAL_MS)
                // REST sigue disponible aunque el WebSocket esté en ERROR (común en Render free).
                if (cloudStatusRelay.value.status != CloudSyncStatus.OFFLINE) {
                    refreshMetaFromServer()
                    refreshConfirmedOrdersFlow()
                }
            }
        }
    }

    /** Respaldo para que la tasa BCV manual del Admin llegue sin depender del WebSocket. */
    private fun startMetaPolling() {
        metaPollJob?.cancel()
        metaPollJob = appScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(META_POLL_INTERVAL_MS)
                if (cloudStatusRelay.value.status != CloudSyncStatus.OFFLINE) {
                    refreshMetaIfChanged()
                }
            }
        }
    }

    /**
     * Sincronización global por sucursal: el Admin guardó la tasa BCV
     * (WebSocket `inventory`, FCM `meta_updated` o polling).
     */
    suspend fun refreshMetaFromBranchEvent(force: Boolean = false) = withContext(Dispatchers.IO) {
        if (!force) {
            val now = System.currentTimeMillis()
            if (now - lastBranchMetaRefreshAt < BRANCH_META_REFRESH_DEBOUNCE_MS) return@withContext
            lastBranchMetaRefreshAt = now
        } else {
            lastBranchMetaRefreshAt = System.currentTimeMillis()
        }
        repeat(META_BRANCH_FETCH_RETRIES) { attempt ->
            runCatching {
                val state = cloudSync.get("/v1/state")
                applyMetaFromStateResponse(state)
                val metaJson = state.optJSONObject("meta")
                val updateAt = metaJson?.optLongOrNull("lastMetaUpdateAt") ?: 0L
                val fetchedAt = metaJson?.optLongOrNull("bcvFetchedAt") ?: 0L
                val rate = metaJson?.optDoubleOrNull("bcvRate") ?: 0.0
                val ordersResetAt = metaJson?.optLongOrNull("lastOrdersResetAt") ?: 0L
                val signature = "$updateAt:$fetchedAt:$rate:$ordersResetAt"
                if (signature != cachedMetaSyncSignature || attempt == META_BRANCH_FETCH_RETRIES - 1) {
                    cachedMetaSyncSignature = signature
                    return@withContext
                }
            }.onFailure { error ->
                if (attempt == META_BRANCH_FETCH_RETRIES - 1) {
                    Log.w(TAG, "No se pudo refrescar meta de sucursal", error)
                }
            }
            delay(META_BRANCH_FETCH_RETRY_MS)
        }
    }

    private suspend fun refreshMetaIfChanged() {
        runCatching {
            val state = cloudSync.get("/v1/state")
            val metaJson = state.optJSONObject("meta")
            val updateAt = metaJson?.optLongOrNull("lastMetaUpdateAt") ?: 0L
            val fetchedAt = metaJson?.optLongOrNull("bcvFetchedAt") ?: 0L
            val rate = metaJson?.optDoubleOrNull("bcvRate") ?: 0.0
            val ordersResetAt = metaJson?.optLongOrNull("lastOrdersResetAt") ?: 0L
            val signature = "$updateAt:$fetchedAt:$rate:$ordersResetAt"
            if (signature != cachedMetaSyncSignature) {
                cachedMetaSyncSignature = signature
                applyMetaFromStateResponse(state)
            }
        }.onFailure { error ->
            Log.w(TAG, "No se pudo consultar meta en polling", error)
        }
    }

    /** Respaldo para que Admin/Supervisor vean cierres PENDING sin depender del WebSocket. */
    private fun startCashClosingsPolling() {
        cashClosingsPollJob?.cancel()
        cashClosingsPollJob = appScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(CASH_CLOSINGS_POLL_INTERVAL_MS)
                if (cloudStatusRelay.value.status != CloudSyncStatus.OFFLINE) {
                    refreshCashClosingsIfChanged()
                }
            }
        }
    }

    /**
     * Sincronización global por sucursal: cualquier usuario registró o validó
     * un cierre (WebSocket `cashClosings` o FCM `cash_closings_updated`).
     */
    suspend fun refreshCashClosingsFromBranchEvent(force: Boolean = false) = withContext(Dispatchers.IO) {
        if (!force) {
            val now = System.currentTimeMillis()
            if (now - lastBranchCashClosingsRefreshAt < BRANCH_CASH_CLOSINGS_REFRESH_DEBOUNCE_MS) {
                return@withContext
            }
            lastBranchCashClosingsRefreshAt = now
        } else {
            lastBranchCashClosingsRefreshAt = System.currentTimeMillis()
        }
        repeat(CASH_CLOSINGS_BRANCH_FETCH_RETRIES) { attempt ->
            val emitted = refreshCashClosingsIfChanged(forceEmit = attempt == CASH_CLOSINGS_BRANCH_FETCH_RETRIES - 1)
            if (emitted) return@withContext
            delay(CASH_CLOSINGS_BRANCH_FETCH_RETRY_MS)
        }
    }

    private suspend fun refreshCashClosingsIfChanged(forceEmit: Boolean = false): Boolean {
        val closings = runCatching { fetchCashClosings() }.getOrDefault(emptyList())
        val signature = closings.joinToString("|") { closing ->
            "${closing.id}:${closing.status}:${closing.revisionNumber}:${closing.reviewedAt}"
        }
        val changed = signature != cachedCashClosingsSignature
        if (changed) {
            cachedCashClosingsSignature = signature
        }
        if (forceEmit || changed) {
            cloudEventsRelay.emit(CloudEvent.CashClosings)
            return true
        }
        return false
    }

    private suspend fun refreshConfirmedOrdersIfDue() {
        val now = System.currentTimeMillis()
        if (now - lastSyncedOrdersRefreshAt < SYNCED_ORDERS_REFRESH_DEBOUNCE_MS) return
        lastSyncedOrdersRefreshAt = now
        refreshConfirmedOrdersFlow()
    }

    /**
     * Sincronización global por sucursal: cualquier usuario confirmó un pedido
     * (WebSocket `sales` o FCM `sales_updated`).
     */
    suspend fun refreshConfirmedOrdersFromBranchEvent(force: Boolean = false) = withContext(Dispatchers.IO) {
        if (!force) {
            val now = System.currentTimeMillis()
            if (now - lastBranchOrdersRefreshAt < SYNCED_ORDERS_REFRESH_DEBOUNCE_MS) return@withContext
            lastBranchOrdersRefreshAt = now
        } else {
            lastBranchOrdersRefreshAt = System.currentTimeMillis()
        }
        refreshConfirmedOrdersFlow(fromBranchEvent = true)
    }

    /** Tras confirmar un pedido localmente: forzar lectura del servidor sin debounce. */
    suspend fun refreshConfirmedOrdersImmediate() = refreshConfirmedOrdersFromBranchEvent(force = true)

    /**
     * Sincronización global por sucursal: el Admin importó Excel en cualquier
     * dispositivo y el servidor emitió `inventory` (WebSocket o FCM).
     */
    suspend fun refreshInventoryFromBranchEvent() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (now - lastBranchInventoryRefreshAt < BRANCH_INVENTORY_REFRESH_DEBOUNCE_MS) return@withContext
        lastBranchInventoryRefreshAt = now
        refreshInventoryState(fromBranchEvent = true)
        repeat(INVENTORY_BRANCH_FETCH_RETRIES) {
            delay(INVENTORY_BRANCH_FETCH_RETRY_MS)
            refreshInventoryState(fromBranchEvent = true)
        }
    }

    /**
     * Elimina cachés locales de pedidos de otras sucursales. Se invoca al
     * iniciar sesión con perfil Consulta/Ventas para evitar mezclar pedidos
     * guardados cuando el mismo dispositivo se usó antes con otra instancia.
     */
    fun purgeOrderPreviewCachesForOtherBranches(keepBranchId: String) {
        val keep = normalizeBranchId(keepBranchId)
        BranchCatalog(context).branches.forEach { branch ->
            if (normalizeBranchId(branch.id) != keep) {
                orderPreviewsPrefsFor(branch.id).edit().clear().commit()
            }
        }
        legacyOrderPreviewsPrefs().edit().clear().commit()
        if (normalizeBranchId(orderPreviewBranchId) != keep) {
            invalidateLocalOrderPreviewCache()
            confirmedOrdersTodayFlow.value = emptyList()
        }
    }

    fun hasPendingOfflineOrders(): Boolean = pendingOrders.isNotEmpty()

    fun observeAllProducts(): StateFlow<List<Product>> = productsFlow.asStateFlow()

    fun observeMeta(): StateFlow<AppMeta?> = metaFlow.asStateFlow()

    fun observeCloudSyncStatus(): StateFlow<CloudSyncInfo> = cloudStatusRelay.asStateFlow()

    /**
     * Lista de "Pedidos confirmados hoy" siempre consultada al servidor
     * (con detalle de líneas) y refrescada automáticamente ante cada evento
     * "sales" del WebSocket, para que se actualice sola en todos los
     * dispositivos en cuanto cualquiera confirma un pedido.
     */
    fun observeConfirmedOrdersToday(): StateFlow<List<ConfirmedOrderPreview>> =
        confirmedOrdersTodayFlow.asStateFlow()

    /** Eventos push crudos del servidor, para pantallas que necesiten reaccionar a Sales/CashClosings/Users. */
    fun observeCloudEvents(): SharedFlow<CloudEvent> = cloudEventsRelay.asSharedFlow()

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
            refreshInventoryState(force = true)
        }.getOrElse { error ->
            ImportResult(0, 0, listOf(error.toUserMessage("No se pudo importar el archivo.")))
        }
    }

    suspend fun productCount(): Int = productsFlow.value.size

    suspend fun findProduct(id: Long): Product? = productsFlow.value.find { it.id == id }

    suspend fun saveBcvRate(rate: Double, manualOverride: Boolean = true) = withContext(Dispatchers.IO) {
        runCatching {
            val response = cloudSync.putJson(
                "/v1/meta",
                JSONObject().apply {
                    put("bcvRate", rate)
                    put("bcvFetchedAt", System.currentTimeMillis())
                    put("bcvManualOverride", true)
                }
            )
            metaFlow.update { current ->
                (current ?: AppMeta()).copy(
                    bcvRate = response.optDoubleOrNull("bcvRate") ?: rate,
                    bcvFetchedAt = response.optLongOrNull("bcvFetchedAt"),
                    bcvManualOverride = true
                )
            }
            metaFlow.value?.let { meta ->
                meta.bcvRate?.takeIf { it > 0 }?.let { persistBcvRateToDisk(it, meta.bcvFetchedAt) }
                val fetchedAt = meta.bcvFetchedAt ?: 0L
                cachedMetaSyncSignature = "${response.optLongOrNull("lastMetaUpdateAt") ?: fetchedAt}:$fetchedAt:${meta.bcvRate ?: 0.0}"
            }
        }
    }

    fun currentMeta(): AppMeta? = metaFlow.value

    suspend fun currentBcvRate(): Double? = metaFlow.value?.bcvRate

    /** Actualiza solo meta (tasa BCV, etc.) sin tocar el catálogo en pantalla. */
    suspend fun refreshMetaFromServer() = withContext(Dispatchers.IO) {
        runCatching {
            applyMetaFromStateResponse(cloudSync.get("/v1/state"))
        }.onFailure { error ->
            Log.w(TAG, "No se pudo refrescar meta; se mantiene la tasa en caché", error)
        }
    }

    suspend fun saveDiscountPercent(percent: Double) = withContext(Dispatchers.IO) {
        runCatching {
            val response = cloudSync.putJson(
                "/v1/meta",
                JSONObject().apply { put("discountPercent", percent) }
            )
            metaFlow.update { current ->
                (current ?: AppMeta()).copy(
                    discountPercent = response.optDoubleOrNull("discountPercent") ?: percent
                )
            }
        }
    }

    /** Búsqueda de solo lectura por código, usada para validar antes de confirmar la venta. */
    suspend fun findDiscountTicket(code: String): Result<DiscountTicket?> = withContext(Dispatchers.IO) {
        runCatching {
            cloudSync.get("/v1/discount-tickets", mapOf("code" to code.trim().uppercase()))
                .optJSONObject("ticket")
                ?.toDiscountTicket()
        }
    }

    /** Emite un cupón en el servidor (Supervisor/Admin). Sin datos personales. */
    suspend fun issueDiscountTicket(
        sourceSaleSyncId: String? = null,
        discountPercent: Double? = null
    ): Result<DiscountTicket> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().apply {
                put("channel", "APP")
                sourceSaleSyncId?.let { put("sourceSaleSyncId", it) }
                discountPercent?.let { put("discountPercent", it) }
            }
            cloudSync.postJson("/v1/discount-tickets", body).toDiscountTicket()
        }
    }

    /** Activa un cupón escaneado (primer escaneo desde la app). */
    suspend fun activateDiscountTicket(code: String): Result<DiscountTicket> = withContext(Dispatchers.IO) {
        runCatching {
            cloudSync.patchJson(
                "/v1/discount-tickets/${code.trim().uppercase()}/activate",
                JSONObject()
            ).toDiscountTicket()
        }
    }

    /** Ejecuta un cupón activo (segundo escaneo): captura teléfono y marca como usado. */
    suspend fun executeDiscountTicket(code: String, telefonoEjecucion: String): Result<DiscountTicket> =
        withContext(Dispatchers.IO) {
            runCatching {
                cloudSync.patchJson(
                    "/v1/discount-tickets/${code.trim().uppercase()}/execute",
                    JSONObject().apply { put("telefono_ejecucion", telefonoEjecucion.trim()) }
                ).toDiscountTicket()
            }
        }

    /**
     * Descuenta stock y registra la venta directamente en el servidor. Si el
     * servidor no es alcanzable (sin internet, cold start, etc.) el pedido se
     * confirma localmente con descuento optimista y se reintenta en cuanto la
     * conexión en tiempo real vuelva a sincronizarse.
     */
    suspend fun executeOrder(
        lines: List<OrderLine>,
        orderCasheaLevel: CasheaCalculator.CasheaLevel? = null,
        discountTicket: DiscountTicket? = null,
        manualDiscountUsd: Double = 0.0
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val catalog = productsFlow.value
            val now = System.currentTimeMillis()
            val resolvedLines = lines.map { line ->
                val product = productForConfirmation(line, catalog)
                val catalogMatch = resolveWithStaleIds(line, catalog)
                if (catalogMatch != null && catalogMatch.quantity < line.quantity) {
                    error(
                        "Stock insuficiente para \"${line.description}\" " +
                            "(disponible ${catalogMatch.quantity}, pedido ${line.quantity})."
                    )
                }
                line.copy(
                    productId = product.id,
                    productSyncId = product.syncId.ifBlank { line.productSyncId },
                    description = line.description.ifBlank { product.description }
                )
            }
            val subtotalUsd = resolvedLines.sumOf { it.totalUsd }
            val couponDiscountUsd = discountTicket?.let { subtotalUsd * it.discountPercent / 100.0 } ?: 0.0
            val manualDiscount = manualDiscountUsd.coerceAtLeast(0.0)
            val discountUsd = (couponDiscountUsd + manualDiscount).coerceAtMost(subtotalUsd)
            val totalUsd = (subtotalUsd - discountUsd).coerceAtLeast(0.0)
            val rate = metaFlow.value?.bcvRate ?: 0.0
            val sale = SaleRecord(
                syncId = CloudSync.newSyncId(),
                createdAt = now,
                totalUsd = totalUsd,
                bcvRate = rate,
                subtotalUsd = subtotalUsd,
                discountUsd = discountUsd
            )
            val pending = PendingOrder(
                lines = resolvedLines,
                sale = sale,
                orderCasheaLevel = orderCasheaLevel,
                discountTicketCode = discountTicket?.code,
                discountUsd = discountUsd,
                subtotalUsd = subtotalUsd
            )
            val previewLines = resolvedLines.map { line ->
                line.copy(casheaLevel = null).toSaleLineItem(sale.syncId, rate)
            }.toMutableList()
            if (orderCasheaLevel != null && rate > 0 && previewLines.isNotEmpty()) {
                CasheaCalculator.lineDetail(totalUsd, rate, orderCasheaLevel)?.let { detail ->
                    previewLines[0] = previewLines[0].copy(
                        casheaLevelLabel = detail.level.label,
                        casheaInitialUsd = detail.initialUsd,
                        casheaInitialBs = detail.initialBs,
                        casheaPendingUsd = detail.pendingUsd,
                        casheaPendingBs = detail.pendingBs,
                        casheaInstallments = detail.installmentCount
                    )
                }
            }
            val preview = ConfirmedOrderPreview(
                syncId = sale.syncId,
                createdAt = sale.createdAt,
                totalUsd = sale.totalUsd,
                bcvRate = sale.bcvRate,
                lines = previewLines,
                subtotalUsd = subtotalUsd,
                discountUsd = discountUsd
            )

            // Descuento optimista en cuanto el pedido es válido: el usuario ve el
            // stock actualizado al instante y el carrito puede vaciarse sin
            // esperar la respuesta del servidor.
            applyLocalDeduction(resolvedLines, now)
            markLocalInventoryMutation()

            // Persistir de inmediato para que Cierre de caja y otras pantallas
            // vean el pedido aunque el push al servidor falle o tarde.
            saveLocalOrderPreview(preview)
            upsertConfirmedOrderInFlow(preview)

            val pushResult = runCatching { pushOrder(pending) }
            pushResult.onFailure { error ->
                if (error.isConnectivityIssue() || error.isMissingCatalogProduct()) {
                    pendingOrders.addLast(pending)
                } else {
                    revertLocalDeduction(resolvedLines, now)
                    removeConfirmedOrderPreview(sale.syncId)
                    throw error
                }
            }
            if (pushResult.isSuccess) {
                refreshConfirmedOrdersImmediate()
            }
            sale.syncId
        }
    }

    suspend fun totalSalesToday(): Double = withContext(Dispatchers.IO) {
        val (start, end) = todayBounds()
        fetchSales(start, end).sumOf { it.totalUsd }
    }

    suspend fun totalDiscountsToday(): Double = withContext(Dispatchers.IO) {
        val (start, end) = todayBounds()
        fetchSales(start, end).sumOf { it.discountUsd }
    }

    suspend fun totalGrossSalesToday(): Double = withContext(Dispatchers.IO) {
        val (start, end) = todayBounds()
        fetchSales(start, end).sumOf { it.subtotalUsd.takeIf { sub -> sub > 0 } ?: it.totalUsd }
    }

    suspend fun confirmedOrdersToday(): Int = withContext(Dispatchers.IO) {
        mergedConfirmedOrdersToday().merged.size
    }

    suspend fun confirmedOrdersTodayDetails(): List<ConfirmedOrderPreview> = withContext(Dispatchers.IO) {
        mergedConfirmedOrdersToday().merged
    }

    suspend fun refreshConfirmedOrdersToday() = withContext(Dispatchers.IO) {
        refreshConfirmedOrdersFlow()
    }

    fun currentConfirmedOrdersToday(): List<ConfirmedOrderPreview> =
        confirmedOrdersTodayFlow.value

    private suspend fun mergedConfirmedOrdersToday(): MergedOrdersResult {
        val (start, end) = todayBounds()
        // Siempre se consulta el historial completo y se filtra en cliente:
        // evita perder pedidos de otros usuarios por desfases de rango en el servidor.
        val fetch = fetchConfirmedOrdersWithStatus(null, null)
        val serverOrders = fetch.orders.filter { it.createdAt in start until end }
        val localOrders = localOrdersForMerge(start, end)
        val merged = mergeConfirmedOrderPreviews(serverOrders, localOrders)
        return MergedOrdersResult(
            merged = merged,
            serverFetchSuccess = fetch.success,
            serverOrderCount = serverOrders.size,
            lastSalesUpdateAt = fetch.lastSalesUpdateAt,
            lastOrdersResetAt = fetch.lastOrdersResetAt
        )
    }

    private suspend fun resolveConfirmedOrdersMerged(fromBranchEvent: Boolean): MergedOrdersResult {
        var result = mergedConfirmedOrdersToday()
        // Reintentos también en polling: Render/Neon pueden devolver datos viejos en el primer fetch.
        val maxRetries = if (fromBranchEvent) SALES_BRANCH_FETCH_RETRIES else 2
        repeat(maxRetries) {
            delay(SALES_BRANCH_FETCH_RETRY_MS)
            val fresher = mergedConfirmedOrdersToday()
            if (!fresher.serverFetchSuccess) return@repeat
            if (fresher.serverOrderCount > result.serverOrderCount ||
                fresher.merged.size > result.merged.size ||
                (fresher.lastSalesUpdateAt ?: 0L) > (result.lastSalesUpdateAt ?: 0L)
            ) {
                result = fresher
            }
        }
        return result
    }

    private fun upsertConfirmedOrderInFlow(preview: ConfirmedOrderPreview) {
        val updated = (confirmedOrdersTodayFlow.value.filter { it.syncId != preview.syncId } + preview)
            .sortedByDescending { it.createdAt }
        confirmedOrdersTodayFlow.value = updated
    }

    private fun removeConfirmedOrderPreview(syncId: String) {
        confirmedOrdersTodayFlow.value =
            confirmedOrdersTodayFlow.value.filter { it.syncId != syncId }
        val remaining = loadLocalOrderPreviews().filter { it.syncId != syncId }
        if (remaining.isEmpty()) {
            clearLocalOrderPreviews()
        } else {
            persistBranchOrderPreviews(remaining)
        }
    }

    private fun markLocalInventoryMutation() {
        protectLocalInventoryUntil = System.currentTimeMillis() + LOCAL_INVENTORY_PROTECT_MS
    }

    private suspend fun refreshConfirmedOrdersFlow(fromBranchEvent: Boolean = false) {
        val result = resolveConfirmedOrdersMerged(fromBranchEvent)
        if (!result.serverFetchSuccess) {
            Log.w(TAG, "Pedidos: consulta al servidor falló; se mantiene la lista en pantalla")
            return
        }

        val current = confirmedOrdersTodayFlow.value
        val (start, end) = todayBounds()
        val localToday = loadLocalOrderPreviewsForToday(start, end)
        val today = todayDayKey()
        val storedDay = todayOrderPreviewsPrefs().getString("day", null)
        if (storedDay != null && storedDay != today) {
            todayOrderPreviewsPrefs().edit().clear().apply()
            invalidateLocalOrderPreviewCache()
            if (result.merged.isEmpty() && current.isEmpty()) {
                confirmedOrdersTodayFlow.value = emptyList()
                return
            }
        }

        val serverResetAt = result.lastOrdersResetAt ?: 0L
        if (serverResetAt > acknowledgedOrdersResetAt) {
            acknowledgedOrdersResetAt = serverResetAt
            cachedLastOrdersResetAt = serverResetAt
            pendingOrders.clear()
            clearLocalOrderPreviews()
            confirmedOrdersTodayFlow.value = result.merged
            Log.i(TAG, "Pedidos reiniciados en sucursal (lastOrdersResetAt=$serverResetAt)")
            return
        }

        if (System.currentTimeMillis() < ordersResetGraceUntil) {
            pendingOrders.clear()
            clearLocalOrderPreviews()
            confirmedOrdersTodayFlow.value = emptyList()
            return
        }

        // Nunca borrar pedidos solo porque el servidor respondió vacío (Render, red, etc.).
        // Solo un reinicio explícito (resetTodayOrders / lastOrdersResetAt) limpia la lista.
        if (result.merged.isEmpty()) {
            if (current.isNotEmpty() || localToday.isNotEmpty()) {
                Log.d(
                    TAG,
                    "Pedidos: servidor sin datos; se conservan en pantalla=${current.size}, " +
                        "disco=${localToday.size}"
                )
                return
            }
        }

        val currentIds = current.map { it.syncId }.toSet()
        val hasNewServerOrders = result.merged.any { it.syncId !in currentIds }
        val salesActivityAdvanced = result.lastSalesUpdateAt != null &&
            result.lastSalesUpdateAt > cachedLastSalesUpdateAt
        val toApply = when {
            result.merged.isEmpty() -> current
            current.isEmpty() -> result.merged
            else -> {
                val mergedIds = result.merged.map { it.syncId }.toSet()
                val localExtra = current.filter { it.syncId !in mergedIds }
                if (localExtra.isEmpty()) {
                    result.merged
                } else {
                    mergeConfirmedOrderPreviews(result.merged, localExtra)
                }
            }
        }
        val serverHasMoreOrders = result.serverOrderCount > current.size
        if (!hasNewServerOrders &&
            !salesActivityAdvanced &&
            !serverHasMoreOrders &&
            toApply == current
        ) {
            if (fromBranchEvent && result.serverOrderCount > 0) {
                Log.d(TAG, "Pedidos de sucursal ya actualizados (${result.serverOrderCount} en servidor)")
            }
            return
        }

        result.lastSalesUpdateAt?.takeIf { it > 0L }?.let { updatedAt ->
            cachedLastSalesUpdateAt = updatedAt
        }

        val hasNewOrders = toApply.any { order -> current.none { it.syncId == order.syncId } }
        if (toApply.isNotEmpty()) {
            persistBranchOrderPreviews(toApply)
        }
        if (toApply != current) {
            confirmedOrdersTodayFlow.value = toApply
        }
        if (fromBranchEvent && hasNewOrders) {
            Log.i(
                TAG,
                "Pedidos de sucursal sincronizados: ${result.merged.size} pedido(s) hoy " +
                    "(servidor=${result.serverOrderCount})"
            )
        }
    }

    suspend fun resetTodayOrders() = withContext(Dispatchers.IO) {
        val (start, end) = todayBounds()
        val query = mapOf("start" to start.toString(), "end" to end.toString())
        val resetBody = JSONObject().apply {
            put("start", start)
            put("end", end)
        }
        val response = runCatching {
            cloudSync.postJson("/v1/sales/reset", resetBody)
        }.recoverCatching { err ->
            if (err is ApiException && (err.code == 404 || err.code == 403)) {
                cloudSync.delete("/v1/sales", query)
            } else {
                throw err
            }
        }.getOrThrow()
        val resetAt = response.optLongOrNull("lastOrdersResetAt")
            ?: runCatching {
                cloudSync.get("/v1/state")
                    .optJSONObject("meta")
                    ?.optLongOrNull("lastOrdersResetAt")
            }.getOrNull()
            ?: System.currentTimeMillis()
        acknowledgedOrdersResetAt = resetAt
        cachedLastOrdersResetAt = resetAt
        ordersResetGraceUntil = System.currentTimeMillis() + ORDERS_RESET_GRACE_MS
        pendingOrders.clear()
        clearLocalOrderPreviews()
        confirmedOrdersTodayFlow.value = emptyList()
    }

    /**
     * Fuerza lectura del servidor (pedidos, inventario, tasa BCV, cierres).
     * Usado por el botón «Refrescar» visible para todos los perfiles.
     */
    suspend fun forceRefreshFromServer(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            refreshMetaFromBranchEvent(force = true)
            refreshInventoryState(force = true, fromBranchEvent = true)
            refreshConfirmedOrdersFromBranchEvent(force = true)
            refreshCashClosingsFromBranchEvent(force = true)
        }
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
        // Cola de aprobación: cualquier PENDING cuenta, no solo los de hoy.
        fetchCashClosings().any { it.status == CashClosingStatus.PENDING }
    }

    suspend fun pendingClosingsCount(): Int = withContext(Dispatchers.IO) {
        fetchCashClosings().count { it.status == CashClosingStatus.PENDING }
    }

    /**
     * Historial de cierres (hoy + días anteriores) para Supervisor/Admin.
     * Sin filtro de fechas: el servidor devuelve toda la colección; el cliente
     * ordena por más reciente.
     */
    suspend fun listClosingHistory(): List<CashClosingRecord> = withContext(Dispatchers.IO) {
        fetchCashClosings().sortedByDescending { it.closedAt }
    }

    suspend fun todayCashClosings(): List<CashClosingRecord> = withContext(Dispatchers.IO) {
        val (dayStart, dayEnd) = todayBounds()
        fetchCashClosings(dayStart, dayEnd)
    }

    suspend fun latestClosingToday(username: String): CashClosingRecord? = withContext(Dispatchers.IO) {
        val normalized = username.trim().lowercase()
        val (dayStart, dayEnd) = todayBounds()
        fetchCashClosings(dayStart, dayEnd)
            .filter { it.username == normalized }
            .maxByOrNull { it.closedAt }
    }

    suspend fun maxRevisionToday(username: String): Int = withContext(Dispatchers.IO) {
        val normalized = username.trim().lowercase()
        val (dayStart, dayEnd) = todayBounds()
        fetchCashClosings(dayStart, dayEnd)
            .filter { it.username == normalized }
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
        pending.lines.forEachIndexed { index, line ->
            val syncId = line.productSyncId.takeIf { it.isNotBlank() }
                ?: error("Producto sin identificador de nube: ${line.description}")
            orderLines.put(
                JSONObject().apply {
                    put("productSyncId", syncId)
                    put("description", line.description)
                    put("quantity", line.quantity)
                    put("unit", line.unit)
                    put("unitPriceUsd", line.unitPriceUsd)
                    put("totalUsd", line.totalUsd)
                    val casheaDetail = when {
                        pending.orderCasheaLevel != null && index == 0 -> {
                            CasheaCalculator.lineDetail(
                                pending.sale.totalUsd,
                                pending.sale.bcvRate,
                                pending.orderCasheaLevel
                            )
                        }
                        else -> line.casheaLevel?.let { level ->
                            CasheaCalculator.lineDetail(line.totalUsd, pending.sale.bcvRate, level)
                        }
                    }
                    casheaDetail?.let { detail ->
                        put("casheaLevelLabel", detail.level.label)
                        put("casheaInitialUsd", detail.initialUsd)
                        put("casheaInitialBs", detail.initialBs)
                        put("casheaPendingUsd", detail.pendingUsd)
                        put("casheaPendingBs", detail.pendingBs)
                        put("casheaInstallments", detail.installmentCount)
                    }
                }
            )
        }
        cloudSync.postJson(
            "/v1/orders",
            JSONObject().apply {
                put("syncId", pending.sale.syncId)
                put("createdAt", pending.sale.createdAt)
                put("totalUsd", pending.sale.totalUsd)
                put("subtotalUsd", pending.subtotalUsd)
                put("bcvRate", pending.sale.bcvRate)
                put("lines", orderLines)
                if (pending.discountUsd > 0) {
                    put("discountUsd", pending.discountUsd)
                }
                pending.discountTicketCode?.let { code ->
                    put("discountTicketCode", code)
                }
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
                val err = result.exceptionOrNull()
                if (err?.isConnectivityIssue() != true && err?.isMissingCatalogProduct() != true) {
                    pendingOrders.removeFirst()
                }
                return
            }
        }
        refreshConfirmedOrdersImmediate()
    }

    private fun applyLocalDeduction(lines: List<OrderLine>, now: Long) {
        productsFlow.update { products ->
            products.map { product ->
                val qtyToDeduct = lines.filter { it.matchesProduct(product) }.sumOf { it.quantity }
                if (qtyToDeduct > 0) {
                    product.copy(
                        quantity = (product.quantity - qtyToDeduct).coerceAtLeast(0.0),
                        updatedAt = now
                    )
                } else {
                    product
                }
            }
        }
        persistProductsToDisk(productsFlow.value)
    }

    private fun revertLocalDeduction(lines: List<OrderLine>, now: Long) {
        productsFlow.update { products ->
            products.map { product ->
                val qtyToRestore = lines.filter { it.matchesProduct(product) }.sumOf { it.quantity }
                if (qtyToRestore > 0) {
                    product.copy(quantity = product.quantity + qtyToRestore, updatedAt = now)
                } else {
                    product
                }
            }
        }
        persistProductsToDisk(productsFlow.value)
    }

    /**
     * La boleta ya tiene el producto que el usuario agregó. Se intenta
     * emparejar con el inventario visible; si el catálogo se refrescó en
     * segundo plano, se confirma igual con los datos de la línea.
     */
    private fun productForConfirmation(line: OrderLine, catalog: List<Product>): Product =
        resolveWithStaleIds(line, catalog)
            ?: Product(
                id = line.productId.takeIf { it != 0L }
                    ?: stableProductId(line.productSyncId.ifBlank { line.description }),
                syncId = line.productSyncId,
                description = line.description,
                quantity = line.quantity,
                unit = line.unit,
                price = line.unitPriceUsd
            )

    private fun resolveProductForOrder(line: OrderLine, products: List<Product>): Product? =
        findProductInCatalog(
            products = products,
            productSyncId = line.productSyncId,
            productId = line.productId,
            description = line.description
        )

    private fun resolveWithStaleIds(line: OrderLine, catalog: List<Product>): Product? =
        resolveProductForOrder(line, catalog)
            ?: resolveProductForOrder(
                line.copy(productSyncId = "", productId = 0L),
                catalog
            )

    private fun inventoryDiskKey(): String =
        "inventory_${normalizeBranchId(orderPreviewBranchId ?: cloudSync.branchId ?: "default")}"

    private fun restoreProductsFromDisk() {
        val prefs = context.getSharedPreferences(INVENTORY_CACHE_PREFS, Context.MODE_PRIVATE)
        val key = inventoryDiskKey()
        cachedLastInventoryUpdateAt = prefs.getLong(inventoryUpdateAtKey(key), 0L)
        cachedInventoryRevision = prefs.getLong(inventoryRevisionKey(key), 0L)
        productsFlow.value = loadProductsFromDisk()
    }

    private fun loadProductsFromDisk(): List<Product> {
        val raw = context.getSharedPreferences(INVENTORY_CACHE_PREFS, Context.MODE_PRIVATE)
            .getString(inventoryDiskKey(), null)
            ?: return emptyList()
        return runCatching { JSONArray(raw).toProductList() }.getOrElse { emptyList() }
    }

    private fun persistProductsToDisk(
        products: List<Product>,
        inventoryUpdateAt: Long? = null,
        inventoryRevision: Long? = null
    ) {
        val prefs = context.getSharedPreferences(INVENTORY_CACHE_PREFS, Context.MODE_PRIVATE).edit()
        val key = inventoryDiskKey()
        if (products.isEmpty()) {
            prefs.remove(key)
            prefs.remove(inventoryUpdateAtKey(key))
            prefs.remove(inventoryRevisionKey(key))
            cachedLastInventoryUpdateAt = 0L
            cachedInventoryRevision = 0L
        } else {
            prefs.putString(key, products.toJsonArray().toString())
            inventoryUpdateAt?.takeIf { it > 0L }?.let { updatedAt ->
                prefs.putLong(inventoryUpdateAtKey(key), updatedAt)
                cachedLastInventoryUpdateAt = updatedAt
            }
            inventoryRevision?.takeIf { it > 0L }?.let { revision ->
                prefs.putLong(inventoryRevisionKey(key), revision)
                cachedInventoryRevision = revision
            }
        }
        prefs.apply()
    }

    private fun inventoryUpdateAtKey(branchKey: String): String = "${branchKey}_updated_at"

    private fun inventoryRevisionKey(branchKey: String): String = "${branchKey}_revision"

    private fun metaDiskKey(): String =
        "meta_bcv_${normalizeBranchId(orderPreviewBranchId ?: cloudSync.branchId ?: "default")}"

    private fun loadBcvRateFromDisk(): Double? =
        context.getSharedPreferences(INVENTORY_CACHE_PREFS, Context.MODE_PRIVATE)
            .getString(metaDiskKey(), null)
            ?.toDoubleOrNull()
            ?.takeIf { it > 0 }

    private fun restoreMetaFromDisk(force: Boolean = false) {
        val rate = loadBcvRateFromDisk()
        val fetchedAt = loadBcvFetchedAtFromDisk().takeIf { it > 0 }
        if (!force) {
            val current = metaFlow.value
            if (rate == null || current?.bcvRate != null && current.bcvRate > 0) return
        }
        metaFlow.value = if (rate != null) {
            (metaFlow.value ?: AppMeta()).copy(bcvRate = rate, bcvFetchedAt = fetchedAt)
        } else {
            null
        }
    }

    private fun persistBcvRateToDisk(rate: Double, fetchedAt: Long? = null) {
        context.getSharedPreferences(INVENTORY_CACHE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(metaDiskKey(), rate.toString())
            .apply {
                fetchedAt?.takeIf { it > 0 }?.let { putLong(metaFetchedAtDiskKey(), it) }
            }
            .apply()
    }

    private fun metaFetchedAtDiskKey(): String = "${metaDiskKey()}_fetched_at"

    private fun loadBcvFetchedAtFromDisk(): Long =
        context.getSharedPreferences(INVENTORY_CACHE_PREFS, Context.MODE_PRIVATE)
            .getLong(metaFetchedAtDiskKey(), 0L)

    private fun applyMetaFromStateResponse(state: JSONObject) {
        val metaJson = state.optJSONObject("meta")
        val current = metaFlow.value
        val serverRate = metaJson?.optDoubleOrNull("bcvRate")?.takeIf { it > 0 }
        val serverFetchedAt = metaJson?.optLongOrNull("bcvFetchedAt") ?: 0L
        val serverSalesUpdateAt = metaJson?.optLongOrNull("lastSalesUpdateAt") ?: 0L
        val localFetchedAt = current?.bcvFetchedAt?.takeIf { it > 0 } ?: loadBcvFetchedAtFromDisk()
        // No pisar la tasa manual con respuestas vacías del servidor (p. ej. Render reiniciado).
        // Si el servidor trae tasa, siempre prevalece cuando es igual o más reciente.
        val resolvedRate = when {
            serverRate != null && serverFetchedAt > 0 && serverFetchedAt >= localFetchedAt -> serverRate
            serverRate != null -> serverRate
            else -> current?.bcvRate?.takeIf { it > 0 } ?: loadBcvRateFromDisk()
        }
        val resolvedFetchedAt = when {
            serverFetchedAt > 0 -> serverFetchedAt
            else -> current?.bcvFetchedAt
        }
        metaFlow.value = AppMeta(
            bcvRate = resolvedRate,
            bcvFetchedAt = resolvedFetchedAt,
            bcvManualOverride = when {
                serverRate != null -> metaJson?.optBoolean("bcvManualOverride", false) ?: false
                else -> current?.bcvManualOverride ?: false
            },
            lastInventoryUpdateAt = metaJson?.optLongOrNull("lastInventoryUpdateAt")
                ?: current?.lastInventoryUpdateAt,
            discountPercent = metaJson?.optDoubleOrNull("discountPercent")
                ?: current?.discountPercent
        )
        resolvedRate?.let { persistBcvRateToDisk(it, resolvedFetchedAt) }
        val serverOrdersResetAt = metaJson?.optLongOrNull("lastOrdersResetAt") ?: 0L
        // Respaldo cross-device: reinicio de pedidos (DELETE /v1/sales).
        if (serverOrdersResetAt > cachedLastOrdersResetAt) {
            appScope.launch {
                refreshConfirmedOrdersFromBranchEvent(force = true)
            }
        }
        // Respaldo cross-device: si otro usuario confirmó un pedido, el meta avanza
        // aunque el evento WebSocket `sales` no llegue (Render free, red móvil).
        if (serverSalesUpdateAt > cachedLastSalesUpdateAt) {
            appScope.launch {
                refreshConfirmedOrdersFromBranchEvent(force = true)
            }
        }
    }

    private suspend fun refreshInventoryState(
        force: Boolean = false,
        fromBranchEvent: Boolean = false
    ) {
        runCatching {
            val state = cloudSync.get("/v1/state")
            val metaJson = state.optJSONObject("meta")
            val serverInventoryUpdateAt = metaJson?.optLongOrNull("lastInventoryUpdateAt") ?: 0L
            val serverRevision = state.optLong("inventoryRevision", 0L)
            val newProducts = (state.optJSONArray("products") ?: JSONArray()).toProductList()
            val currentProducts = productsFlow.value

            applyMetaFromStateResponse(state)

            if (newProducts.isEmpty()) {
                if (force && currentProducts.isNotEmpty()) {
                    Log.w(
                        TAG,
                        "Inventario: refresh forzado ignoró respuesta vacía (en pantalla=${currentProducts.size})"
                    )
                } else if (!force && currentProducts.isNotEmpty()) {
                    Log.w(
                        TAG,
                        "Inventario: respuesta vacía ignorada (en pantalla=${currentProducts.size})"
                    )
                }
                return@runCatching
            }

            val revisionAdvanced = serverRevision > cachedInventoryRevision
            val catalogChanged = inventoryCatalogChanged(currentProducts, newProducts)
            val serverCatalogNewer = serverInventoryUpdateAt > cachedLastInventoryUpdateAt
            val localMutationProtected = System.currentTimeMillis() < protectLocalInventoryUntil

            val shouldReplace = when {
                localMutationProtected && !revisionAdvanced -> false
                force -> true
                fromBranchEvent -> true
                currentProducts.isEmpty() -> true
                revisionAdvanced -> true
                serverCatalogNewer -> true
                catalogChanged -> true
                else -> false
            }

            if (shouldReplace) {
                productsFlow.value = newProducts
                persistProductsToDisk(
                    newProducts,
                    inventoryUpdateAt = serverInventoryUpdateAt.takeIf { it > 0L },
                    inventoryRevision = serverRevision.takeIf { it > 0L }
                )
                if (revisionAdvanced || serverCatalogNewer || catalogChanged || fromBranchEvent) {
                    Log.i(
                        TAG,
                        "Inventario sincronizado con servidor (${newProducts.size} productos, " +
                            "revision=$serverRevision, lastInventoryUpdateAt=$serverInventoryUpdateAt)"
                    )
                }
            } else if (fromBranchEvent) {
                Log.d(TAG, "Evento inventory: catálogo local ya actualizado (revision=$serverRevision)")
            }
        }.onFailure { error ->
            Log.w(TAG, "No se pudo refrescar inventario; se mantiene la caché local", error)
        }
    }

    // start/end (epoch ms) filtran en el servidor para no transferir todo el
    // historial de ventas/cierres cada vez que solo hace falta el de "hoy":
    // ahorra datos móviles y tráfico del plan free del servidor a medida que
    // crece el historial.
    private suspend fun fetchSales(start: Long? = null, end: Long? = null): List<SaleRecord> = runCatching {
        cloudSync.get("/v1/sales", rangeQuery(start, end)).optJSONArray("sales")?.toSaleList() ?: emptyList()
    }.getOrDefault(emptyList())

    private suspend fun fetchConfirmedOrdersWithStatus(
        start: Long? = null,
        end: Long? = null
    ): ConfirmedOrdersFetch = runCatching {
        val response = cloudSync.get("/v1/sales", rangeQuery(start, end))
        val sales = response.optJSONArray("sales")?.toSaleList() ?: emptyList()
        val lineItems = response.optJSONArray("lineItems")?.toSaleLineItemList() ?: emptyList()
        val metaJson = response.optJSONObject("meta")
        ConfirmedOrdersFetch(
            orders = buildConfirmedOrderPreviews(sales, lineItems),
            success = true,
            lastSalesUpdateAt = metaJson?.optLongOrNull("lastSalesUpdateAt"),
            lastOrdersResetAt = metaJson?.optLongOrNull("lastOrdersResetAt")
        )
    }.getOrElse { error ->
        Log.w(TAG, "No se pudieron cargar pedidos confirmados del servidor", error)
        ConfirmedOrdersFetch(emptyList(), success = false)
    }

    private fun localOrdersForMerge(start: Long, end: Long): List<ConfirmedOrderPreview> {
        val pendingIds = pendingOrders.mapTo(HashSet()) { it.sale.syncId }
        if (pendingIds.isEmpty()) return emptyList()
        return loadLocalOrderPreviewsForToday(start, end).filter { it.syncId in pendingIds }
    }

    private fun restoreConfirmedOrdersFromDisk() {
        val (start, end) = todayBounds()
        val cached = loadLocalOrderPreviewsForToday(start, end)
        if (cached.isNotEmpty()) {
            confirmedOrdersTodayFlow.value = cached
        }
    }

    private suspend fun fetchCashClosings(start: Long? = null, end: Long? = null): List<CashClosingRecord> = runCatching {
        cloudSync.get("/v1/cash-closings", rangeQuery(start, end)).optJSONArray("cashClosings")?.toCashClosingList()
            ?: emptyList()
    }.getOrDefault(emptyList())

    private fun rangeQuery(start: Long?, end: Long?): Map<String, String> =
        if (start != null && end != null) mapOf("start" to start.toString(), "end" to end.toString()) else emptyMap()

    // /v1/orders es idempotente (por syncId), así que cualquier error 5xx del
    // servidor de sincronización es seguro de reintentar en cuanto vuelva la
    // conexión, sin riesgo de descontar stock dos veces.
    private fun Throwable.isConnectivityIssue(): Boolean =
        this is ApiException && (code == 0 || code in 500..599) ||
            this !is ApiException

    private fun Throwable.isMissingCatalogProduct(): Boolean =
        message.orEmpty().contains("Producto no encontrado", ignoreCase = true)

    private fun todayBounds(): Pair<Long, Long> {
        val startOfDay = ZonedDateTime.now(CARACAS_ZONE).toLocalDate().atStartOfDay(CARACAS_ZONE)
        val endOfDay = startOfDay.plusDays(1)
        return startOfDay.toInstant().toEpochMilli() to endOfDay.toInstant().toEpochMilli()
    }

    private fun todayDayKey(): String {
        val date = ZonedDateTime.now(CARACAS_ZONE).toLocalDate()
        return "${date.year}-${date.dayOfYear}"
    }

    private fun orderPreviewsPrefsFor(branchId: String?): android.content.SharedPreferences =
        context.getSharedPreferences(orderPreviewsPrefsName(branchId), Context.MODE_PRIVATE)

    private fun todayOrderPreviewsPrefs(): android.content.SharedPreferences =
        orderPreviewsPrefsFor(cloudSync.branchId)

    private fun legacyOrderPreviewsPrefs(): android.content.SharedPreferences =
        context.getSharedPreferences(LEGACY_ORDER_PREVIEWS_PREFS, Context.MODE_PRIVATE)

    private fun invalidateLocalOrderPreviewCache() {
        cachedLocalOrderDayKey = null
        cachedLocalOrderPreviews = null
    }

    private fun loadLocalOrderPreviews(): List<ConfirmedOrderPreview> {
        val dayKey = todayDayKey()
        if (cachedLocalOrderDayKey == dayKey && cachedLocalOrderPreviews != null) {
            return cachedLocalOrderPreviews!!
        }
        val prefs = todayOrderPreviewsPrefs()
        var loaded = readOrderPreviewsFromPrefs(prefs, dayKey)
        if (loaded.isEmpty()) {
            val legacy = legacyOrderPreviewsPrefs()
            val legacyLoaded = readOrderPreviewsFromPrefs(legacy, dayKey)
            if (legacyLoaded.isNotEmpty()) {
                loaded = legacyLoaded
                prefs.edit()
                    .putString("day", dayKey)
                    .putString(
                        "orders",
                        JSONArray().apply { legacyLoaded.forEach { put(it.toJsonObject()) } }.toString()
                    )
                    .commit()
                legacy.edit().clear().commit()
            }
        }
        cachedLocalOrderDayKey = dayKey
        cachedLocalOrderPreviews = loaded
        return loaded
    }

    private fun readOrderPreviewsFromPrefs(
        prefs: android.content.SharedPreferences,
        dayKey: String
    ): List<ConfirmedOrderPreview> {
        if (prefs.getString("day", null) != dayKey) return emptyList()
        val raw = prefs.getString("orders", null) ?: return emptyList()
        return runCatching { JSONArray(raw).toConfirmedOrderPreviewList() }.getOrDefault(emptyList())
    }

    private fun loadLocalOrderPreviewsForToday(start: Long, end: Long): List<ConfirmedOrderPreview> =
        loadLocalOrderPreviews().filter { it.createdAt in start until end }

    private fun saveLocalOrderPreview(preview: ConfirmedOrderPreview) {
        val current = loadLocalOrderPreviews()
        val updated = (current.filter { it.syncId != preview.syncId } + preview)
            .sortedByDescending { it.createdAt }
        persistBranchOrderPreviews(updated)
    }

    private fun persistBranchOrderPreviews(orders: List<ConfirmedOrderPreview>) {
        val dayKey = todayDayKey()
        cachedLocalOrderDayKey = dayKey
        cachedLocalOrderPreviews = orders
        val payload = JSONArray().apply { orders.forEach { put(it.toJsonObject()) } }
        todayOrderPreviewsPrefs().edit()
            .putString("day", dayKey)
            .putString("orders", payload.toString())
            .commit()
    }

    private fun clearLocalOrderPreviews() {
        cachedLocalOrderDayKey = todayDayKey()
        cachedLocalOrderPreviews = emptyList()
        todayOrderPreviewsPrefs().edit().clear().commit()
        legacyOrderPreviewsPrefs().edit().clear().commit()
    }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { optString(it, null) }

    private data class PendingOrder(
        val lines: List<OrderLine>,
        val sale: SaleRecord,
        val orderCasheaLevel: CasheaCalculator.CasheaLevel? = null,
        val discountTicketCode: String? = null,
        val discountUsd: Double = 0.0,
        val subtotalUsd: Double = sale.totalUsd
    )

    private data class ConfirmedOrdersFetch(
        val orders: List<ConfirmedOrderPreview>,
        val success: Boolean,
        val lastSalesUpdateAt: Long? = null,
        val lastOrdersResetAt: Long? = null
    )

    private data class MergedOrdersResult(
        val merged: List<ConfirmedOrderPreview>,
        val serverFetchSuccess: Boolean,
        val serverOrderCount: Int,
        val lastSalesUpdateAt: Long? = null,
        val lastOrdersResetAt: Long? = null
    )

    companion object {
        const val MAX_CLOSINGS_PER_DAY = 5
        private const val TAG = "InventoryRepository"
        private val CARACAS_ZONE = ZoneId.of("America/Caracas")
        private const val CONFIRMED_ORDERS_POLL_INTERVAL_MS = 8_000L
        private const val INVENTORY_POLL_INTERVAL_MS = 30_000L
        private const val CASH_CLOSINGS_POLL_INTERVAL_MS = 15_000L
        private const val META_POLL_INTERVAL_MS = 10_000L
        private const val SYNCED_ORDERS_REFRESH_DEBOUNCE_MS = 2_000L
        private const val SALES_BRANCH_FETCH_RETRIES = 3
        private const val SALES_BRANCH_FETCH_RETRY_MS = 450L
        private const val INVENTORY_BRANCH_FETCH_RETRIES = 2
        private const val INVENTORY_BRANCH_FETCH_RETRY_MS = 450L
        private const val CASH_CLOSINGS_BRANCH_FETCH_RETRIES = 3
        private const val CASH_CLOSINGS_BRANCH_FETCH_RETRY_MS = 450L
        private const val BRANCH_CASH_CLOSINGS_REFRESH_DEBOUNCE_MS = 1_500L
        private const val META_BRANCH_FETCH_RETRIES = 3
        private const val META_BRANCH_FETCH_RETRY_MS = 450L
        private const val BRANCH_META_REFRESH_DEBOUNCE_MS = 1_000L
        private const val LOCAL_INVENTORY_PROTECT_MS = 4_000L
        private const val ORDERS_RESET_GRACE_MS = 30_000L
        private const val BRANCH_INVENTORY_REFRESH_DEBOUNCE_MS = 1_500L
        private const val INVENTORY_CACHE_PREFS = "inventory_local_cache"
        private const val LEGACY_ORDER_PREVIEWS_PREFS = "today_order_previews"

        private fun orderPreviewsPrefsName(branchId: String?): String {
            val key = branchId?.takeIf { it.isNotBlank() } ?: "default"
            return "today_order_previews_$key"
        }
    }
}
