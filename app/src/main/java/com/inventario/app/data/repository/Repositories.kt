package com.inventario.app.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.inventario.app.data.branch.BranchCatalog
import com.inventario.app.data.branch.normalizeBranchId
import com.inventario.app.data.entity.AppMeta
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
    private var lastSyncedOrdersRefreshAt = 0L
    private var lastBranchOrdersRefreshAt = 0L
    private var lastBranchInventoryRefreshAt = 0L
    private var cachedLastInventoryUpdateAt: Long = 0L
    private var cachedLastSalesUpdateAt: Long = 0L

    init {
        restoreProductsFromDisk()
        restoreConfirmedOrdersFromDisk()
        appScope.launch {
            refreshInventoryState()
        }
        appScope.launch { refreshConfirmedOrdersFlow() }
        attachCloudSyncListeners(cloudSync)
        startConfirmedOrdersPolling()
    }

    fun setCloudSync(sync: CloudSync) {
        val branchChanged = orderPreviewBranchId != null && orderPreviewBranchId != sync.branchId
        cloudSync = sync
        orderPreviewBranchId = sync.branchId
        attachCloudSyncListeners(sync)
        if (branchChanged) {
            metaFlow.value = null
            invalidateLocalOrderPreviewCache()
            confirmedOrdersTodayFlow.value = emptyList()
            restoreProductsFromDisk()
            restoreConfirmedOrdersFromDisk()
            appScope.launch { refreshInventoryState(force = true) }
        } else {
            invalidateLocalOrderPreviewCache()
        }
        appScope.launch { refreshConfirmedOrdersFlow() }
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
                        refreshInventoryFromBranchEvent()
                    }
                    is CloudEvent.Sales -> appScope.launch(Dispatchers.IO) {
                        refreshConfirmedOrdersFromBranchEvent()
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
                }
                if (info.status == CloudSyncStatus.SYNCED && pendingOrders.isNotEmpty()) {
                    flushPendingOrders()
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
                    refreshConfirmedOrdersFlow()
                }
            }
        }
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
            refreshInventoryState(force = true)
            val now = System.currentTimeMillis()
            val currentProducts = productsFlow.value
            val resolvedLines = lines.map { line ->
                val product = resolveProductForOrder(line, currentProducts)
                    ?: error("Producto no encontrado: ${line.description}")
                if (product.quantity < line.quantity) {
                    error(
                        "Stock insuficiente para \"${line.description}\" " +
                            "(disponible ${product.quantity}, pedido ${line.quantity})."
                    )
                }
                line.copy(productId = product.id, productSyncId = product.syncId)
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

            // Persistir de inmediato para que Cierre de caja y otras pantallas
            // vean el pedido aunque el push al servidor falle o tarde.
            saveLocalOrderPreview(preview)
            upsertConfirmedOrderInFlow(preview)

            val pushResult = runCatching { pushOrder(pending) }
            pushResult
                .onSuccess { applyLocalDeduction(resolvedLines, now) }
                .onFailure { error ->
                    if (error.isConnectivityIssue()) {
                        applyLocalDeduction(resolvedLines, now)
                        pendingOrders.addLast(pending)
                    } else {
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
        val fetch = fetchConfirmedOrdersWithStatus(start, end)
        val serverOrders = fetch.orders.filter { it.createdAt in start until end }
        val localOrders = localOrdersForMerge(start, end)
        val merged = mergeConfirmedOrderPreviews(serverOrders, localOrders)
        return MergedOrdersResult(
            merged = merged,
            serverFetchSuccess = fetch.success,
            serverOrderCount = serverOrders.size,
            lastSalesUpdateAt = fetch.lastSalesUpdateAt
        )
    }

    private suspend fun resolveConfirmedOrdersMerged(fromBranchEvent: Boolean): MergedOrdersResult {
        var result = mergedConfirmedOrdersToday()
        if (!fromBranchEvent) return result

        repeat(SALES_BRANCH_FETCH_RETRIES) {
            delay(SALES_BRANCH_FETCH_RETRY_MS)
            val fresher = mergedConfirmedOrdersToday()
            if (!fresher.serverFetchSuccess) return@repeat
            if (fresher.serverOrderCount > result.serverOrderCount ||
                fresher.merged.size > result.merged.size
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
            if (result.merged.isEmpty()) {
                confirmedOrdersTodayFlow.value = emptyList()
                return
            }
        }

        if (result.merged.isEmpty() && (current.isNotEmpty() || localToday.isNotEmpty())) {
            Log.w(
                TAG,
                "Pedidos: servidor vacío; se mantiene caché local " +
                    "(en pantalla=${current.size}, disco=${localToday.size})"
            )
            return
        }

        if (result.merged == current) {
            if (fromBranchEvent && result.serverOrderCount > 0) {
                Log.d(TAG, "Pedidos de sucursal ya actualizados (${result.serverOrderCount} en servidor)")
            }
            return
        }

        result.lastSalesUpdateAt?.takeIf { it > 0L }?.let { updatedAt ->
            cachedLastSalesUpdateAt = updatedAt
        }

        val hasNewOrders = result.merged.any { order -> current.none { it.syncId == order.syncId } }
        if (result.merged.isNotEmpty()) {
            persistBranchOrderPreviews(result.merged)
        }
        confirmedOrdersTodayFlow.value = result.merged
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
        cloudSync.delete("/v1/sales", mapOf("start" to start.toString(), "end" to end.toString()))
        clearLocalOrderPreviews()
    }.also {
        confirmedOrdersTodayFlow.value = emptyList()
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
                if (result.exceptionOrNull()?.isConnectivityIssue() != true) {
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
                val line = lines.find { it.matchesProduct(product) }
                if (line != null) product.copy(quantity = product.quantity - line.quantity, updatedAt = now) else product
            }
        }
        persistProductsToDisk(productsFlow.value)
    }

    /**
     * Resuelve un producto del pedido contra el inventario actual.
     * Prioriza syncId (estable en la nube); si el catálogo se refrescó tras
     * agregar la línea, el productId derivado del hash puede quedar obsoleto.
     */
    private fun resolveProductForOrder(line: OrderLine, products: List<Product>): Product? {
        if (line.productSyncId.isNotBlank()) {
            products.find { it.syncId == line.productSyncId }?.let { return it }
        }
        products.find { it.id == line.productId }?.let { return it }
        val byDescription = products.filter { product ->
            product.description.equals(line.description, ignoreCase = true) ||
                ProductSearch.matchesAllTokens(product.description, line.description)
        }
        return byDescription.singleOrNull()
    }

    private fun inventoryDiskKey(): String =
        "inventory_${normalizeBranchId(orderPreviewBranchId ?: cloudSync.branchId ?: "default")}"

    private fun restoreProductsFromDisk() {
        val prefs = context.getSharedPreferences(INVENTORY_CACHE_PREFS, Context.MODE_PRIVATE)
        val key = inventoryDiskKey()
        cachedLastInventoryUpdateAt = prefs.getLong(inventoryUpdateAtKey(key), 0L)
        val cached = loadProductsFromDisk()
        if (cached.isNotEmpty()) {
            productsFlow.value = cached
        }
    }

    private fun loadProductsFromDisk(): List<Product> {
        val raw = context.getSharedPreferences(INVENTORY_CACHE_PREFS, Context.MODE_PRIVATE)
            .getString(inventoryDiskKey(), null)
            ?: return emptyList()
        return runCatching { JSONArray(raw).toProductList() }.getOrElse { emptyList() }
    }

    private fun persistProductsToDisk(products: List<Product>, inventoryUpdateAt: Long? = null) {
        val prefs = context.getSharedPreferences(INVENTORY_CACHE_PREFS, Context.MODE_PRIVATE).edit()
        val key = inventoryDiskKey()
        if (products.isEmpty()) {
            prefs.remove(key)
            prefs.remove(inventoryUpdateAtKey(key))
            cachedLastInventoryUpdateAt = 0L
        } else {
            prefs.putString(key, products.toJsonArray().toString())
            inventoryUpdateAt?.takeIf { it > 0L }?.let { updatedAt ->
                prefs.putLong(inventoryUpdateAtKey(key), updatedAt)
                cachedLastInventoryUpdateAt = updatedAt
            }
        }
        prefs.apply()
    }

    private fun inventoryUpdateAtKey(branchKey: String): String = "${branchKey}_updated_at"

    private suspend fun refreshInventoryState(
        force: Boolean = false,
        fromBranchEvent: Boolean = false
    ) {
        runCatching {
            val state = cloudSync.get("/v1/state")
            val metaJson = state.optJSONObject("meta")
            val serverInventoryUpdateAt = metaJson?.optLongOrNull("lastInventoryUpdateAt") ?: 0L
            val newProducts = (state.optJSONArray("products") ?: JSONArray()).toProductList()
            val currentProducts = productsFlow.value

            metaFlow.value = AppMeta(
                bcvRate = metaJson?.optDoubleOrNull("bcvRate"),
                bcvFetchedAt = metaJson?.optLongOrNull("bcvFetchedAt"),
                lastInventoryUpdateAt = metaJson?.optLongOrNull("lastInventoryUpdateAt"),
                discountPercent = metaJson?.optDoubleOrNull("discountPercent")
            )

            // Tras importar Excel el servidor asigna syncIds nuevos; si la caché
            // local quedó desfasada, hay que reemplazarla aunque el evento no sea
            // de sucursal o la marca local ya estuviera anclada.
            val serverCatalogNewer = serverInventoryUpdateAt > cachedLastInventoryUpdateAt &&
                newProducts.isNotEmpty()

            val shouldReplace = when {
                force && newProducts.isNotEmpty() -> true
                force -> false
                currentProducts.isEmpty() && newProducts.isNotEmpty() -> true
                serverCatalogNewer -> true
                else -> false
            }

            if (shouldReplace) {
                productsFlow.value = newProducts
                persistProductsToDisk(
                    newProducts,
                    inventoryUpdateAt = serverInventoryUpdateAt.takeIf { it > 0L }
                )
                if (serverCatalogNewer) {
                    Log.i(
                        TAG,
                        "Inventario sincronizado con servidor (${newProducts.size} productos, " +
                            "lastInventoryUpdateAt=$serverInventoryUpdateAt)"
                    )
                }
            } else if (force && newProducts.isEmpty() && currentProducts.isNotEmpty()) {
                Log.w(
                    TAG,
                    "Inventario: refresh forzado ignoró respuesta vacía (en pantalla=${currentProducts.size})"
                )
            } else if (fromBranchEvent && serverInventoryUpdateAt <= cachedLastInventoryUpdateAt) {
                Log.d(TAG, "Evento inventory ignorado: sin nueva carga Excel en la sucursal")
            } else if (!force && newProducts.isEmpty() && currentProducts.isNotEmpty()) {
                Log.w(
                    TAG,
                    "Inventario: respuesta vacía ignorada (en pantalla=${currentProducts.size})"
                )
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
            lastSalesUpdateAt = metaJson?.optLongOrNull("lastSalesUpdateAt")
        )
    }.getOrElse { error ->
        Log.w(TAG, "No se pudieron cargar pedidos confirmados del servidor", error)
        ConfirmedOrdersFetch(emptyList(), success = false)
    }

    private fun localOrdersForMerge(start: Long, end: Long): List<ConfirmedOrderPreview> =
        loadLocalOrderPreviewsForToday(start, end)

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
        val lastSalesUpdateAt: Long? = null
    )

    private data class MergedOrdersResult(
        val merged: List<ConfirmedOrderPreview>,
        val serverFetchSuccess: Boolean,
        val serverOrderCount: Int,
        val lastSalesUpdateAt: Long? = null
    )

    companion object {
        const val MAX_CLOSINGS_PER_DAY = 5
        private const val TAG = "InventoryRepository"
        private val CARACAS_ZONE = ZoneId.of("America/Caracas")
        private const val CONFIRMED_ORDERS_POLL_INTERVAL_MS = 8_000L
        private const val SYNCED_ORDERS_REFRESH_DEBOUNCE_MS = 2_000L
        private const val SALES_BRANCH_FETCH_RETRIES = 3
        private const val SALES_BRANCH_FETCH_RETRY_MS = 450L
        private const val BRANCH_INVENTORY_REFRESH_DEBOUNCE_MS = 1_500L
        private const val INVENTORY_CACHE_PREFS = "inventory_local_cache"
        private const val LEGACY_ORDER_PREVIEWS_PREFS = "today_order_previews"

        private fun orderPreviewsPrefsName(branchId: String?): String {
            val key = branchId?.takeIf { it.isNotBlank() } ?: "default"
            return "today_order_previews_$key"
        }
    }
}
