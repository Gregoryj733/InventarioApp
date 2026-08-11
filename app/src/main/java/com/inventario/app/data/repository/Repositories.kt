package com.inventario.app.data.repository

import android.content.Context
import android.net.Uri
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
import com.inventario.app.data.order.toSaleLineItem
import com.inventario.app.data.search.ProductSearch
import com.inventario.app.data.session.SessionManager
import com.inventario.app.data.sync.ApiException
import com.inventario.app.data.sync.CloudEvent
import com.inventario.app.data.sync.CloudSync
import com.inventario.app.data.sync.CloudSyncInfo
import com.inventario.app.data.sync.CloudSyncStatus
import com.inventario.app.data.sync.toUserMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

sealed class LoginResult {
    data class Success(val user: User) : LoginResult()
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
            LoginResult.Success(response.getJSONObject("user").toUser())
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
    private val confirmedOrdersTodayFlow = MutableStateFlow<List<ConfirmedOrderPreview>>(emptyList())
    private val pendingOrders = ArrayDeque<PendingOrder>()
    private val todayOrderPreviewsPrefs =
        context.getSharedPreferences("today_order_previews", Context.MODE_PRIVATE)
    private var cachedLocalOrderPreviews: List<ConfirmedOrderPreview>? = null
    private var cachedLocalOrderDayKey: String? = null

    init {
        appScope.launch { refreshInventoryState() }
        appScope.launch { refreshConfirmedOrdersFlow() }
        // Cada dispositivo conectado recibe estos eventos por WebSocket en
        // cuanto CUALQUIER usuario confirma un pedido, aprueba/rechaza un
        // cierre o cambia inventario: así todas las pantallas quedan
        // sincronizadas en tiempo real con la base central, sin polling ni
        // recargas manuales.
        appScope.launch {
            cloudSync.events.collect { event ->
                when (event) {
                    is CloudEvent.Inventory -> refreshInventoryState()
                    is CloudEvent.Sales -> refreshConfirmedOrdersFlow()
                    else -> Unit
                }
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
        appScope.launch { refreshConfirmedOrdersFlow() }
    }

    fun observeAllProducts(): StateFlow<List<Product>> = productsFlow.asStateFlow()

    fun observeMeta(): StateFlow<AppMeta?> = metaFlow.asStateFlow()

    fun observeCloudSyncStatus(): StateFlow<CloudSyncInfo> = cloudSync.status

    /**
     * Lista de "Pedidos confirmados hoy" siempre consultada al servidor
     * (con detalle de líneas) y refrescada automáticamente ante cada evento
     * "sales" del WebSocket, para que se actualice sola en todos los
     * dispositivos en cuanto cualquiera confirma un pedido.
     */
    fun observeConfirmedOrdersToday(): StateFlow<List<ConfirmedOrderPreview>> =
        confirmedOrdersTodayFlow.asStateFlow()

    /** Eventos push crudos del servidor, para pantallas que necesiten reaccionar a Sales/CashClosings/Users. */
    fun observeCloudEvents(): SharedFlow<CloudEvent> = cloudSync.events

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
        discountTicket: DiscountTicket? = null
    ): Result<String> = withContext(Dispatchers.IO) {
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
            val subtotalUsd = lines.sumOf { it.totalUsd }
            val discountUsd = discountTicket?.let { subtotalUsd * it.discountPercent / 100.0 } ?: 0.0
            val totalUsd = (subtotalUsd - discountUsd).coerceAtLeast(0.0)
            val rate = metaFlow.value?.bcvRate ?: 0.0
            val sale = SaleRecord(
                syncId = CloudSync.newSyncId(),
                createdAt = now,
                totalUsd = totalUsd,
                bcvRate = rate
            )
            val pending = PendingOrder(
                lines = lines,
                sale = sale,
                productSyncIds = syncIdsByProductId,
                orderCasheaLevel = orderCasheaLevel,
                discountTicketCode = discountTicket?.code,
                discountUsd = discountUsd
            )
            val previewLines = lines.map { line ->
                line.copy(casheaLevel = null).toSaleLineItem(sale.syncId, rate)
            }.toMutableList()
            if (orderCasheaLevel != null && rate > 0 && previewLines.isNotEmpty()) {
                CasheaCalculator.lineDetail(subtotalUsd, rate, orderCasheaLevel)?.let { detail ->
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
                lines = previewLines
            )

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
            saveLocalOrderPreview(preview)
            sale.syncId
        }.onSuccess {
            // No depende solo del evento "sales" por WebSocket (que puede
            // tardar un instante en llegar): este mismo dispositivo refresca
            // la lista de inmediato al confirmar su propio pedido.
            refreshConfirmedOrdersFlow()
        }
    }

    suspend fun totalSalesToday(): Double = withContext(Dispatchers.IO) {
        val (start, end) = todayBounds()
        fetchSales(start, end).sumOf { it.totalUsd }
    }

    suspend fun confirmedOrdersToday(): Int = withContext(Dispatchers.IO) {
        mergedConfirmedOrdersToday().size
    }

    suspend fun confirmedOrdersTodayDetails(): List<ConfirmedOrderPreview> = withContext(Dispatchers.IO) {
        mergedConfirmedOrdersToday()
    }

    suspend fun refreshConfirmedOrdersToday() = withContext(Dispatchers.IO) {
        refreshConfirmedOrdersFlow()
    }

    private suspend fun mergedConfirmedOrdersToday(): List<ConfirmedOrderPreview> {
        val (start, end) = todayBounds()
        val serverOrders = fetchConfirmedOrders(start, end)
        return mergeConfirmedOrderPreviews(serverOrders, loadLocalOrderPreviewsForToday(start, end))
    }

    private suspend fun refreshConfirmedOrdersFlow() {
        confirmedOrdersTodayFlow.value = mergedConfirmedOrdersToday()
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
        val (dayStart, dayEnd) = todayBounds()
        fetchCashClosings(dayStart, dayEnd).any { it.status == CashClosingStatus.PENDING }
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
                put("bcvRate", pending.sale.bcvRate)
                put("lines", orderLines)
                pending.discountTicketCode?.let { code ->
                    put("discountTicketCode", code)
                    put("discountUsd", pending.discountUsd)
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
        refreshInventoryState()
        refreshConfirmedOrdersFlow()
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
                lastInventoryUpdateAt = metaJson?.optLongOrNull("lastInventoryUpdateAt"),
                discountPercent = metaJson?.optDoubleOrNull("discountPercent")
            )
            productsFlow.value = (state.optJSONArray("products") ?: JSONArray()).toProductList()
        }
    }

    // start/end (epoch ms) filtran en el servidor para no transferir todo el
    // historial de ventas/cierres cada vez que solo hace falta el de "hoy":
    // ahorra datos móviles y tráfico del plan free del servidor a medida que
    // crece el historial.
    private suspend fun fetchSales(start: Long? = null, end: Long? = null): List<SaleRecord> = runCatching {
        cloudSync.get("/v1/sales", rangeQuery(start, end)).optJSONArray("sales")?.toSaleList() ?: emptyList()
    }.getOrDefault(emptyList())

    private suspend fun fetchConfirmedOrders(start: Long, end: Long): List<ConfirmedOrderPreview> = runCatching {
        val response = cloudSync.get("/v1/sales", rangeQuery(start, end))
        val sales = response.optJSONArray("sales")?.toSaleList() ?: emptyList()
        val lineItems = response.optJSONArray("lineItems")?.toSaleLineItemList() ?: emptyList()
        buildConfirmedOrderPreviews(sales, lineItems)
    }.getOrDefault(emptyList())

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

    private fun todayDayKey(): String {
        val cal = Calendar.getInstance()
        return "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.DAY_OF_YEAR)}"
    }

    private fun loadLocalOrderPreviews(): List<ConfirmedOrderPreview> {
        val dayKey = todayDayKey()
        if (cachedLocalOrderDayKey == dayKey && cachedLocalOrderPreviews != null) {
            return cachedLocalOrderPreviews!!
        }
        if (todayOrderPreviewsPrefs.getString("day", null) != dayKey) {
            cachedLocalOrderDayKey = dayKey
            cachedLocalOrderPreviews = emptyList()
            return emptyList()
        }
        val raw = todayOrderPreviewsPrefs.getString("orders", null)
        val loaded = raw?.let {
            runCatching { JSONArray(it).toConfirmedOrderPreviewList() }.getOrDefault(emptyList())
        } ?: emptyList()
        cachedLocalOrderDayKey = dayKey
        cachedLocalOrderPreviews = loaded
        return loaded
    }

    private fun loadLocalOrderPreviewsForToday(start: Long, end: Long): List<ConfirmedOrderPreview> =
        loadLocalOrderPreviews().filter { it.createdAt in start until end }

    private fun saveLocalOrderPreview(preview: ConfirmedOrderPreview) {
        val dayKey = todayDayKey()
        val current = loadLocalOrderPreviews()
        val updated = (current.filter { it.syncId != preview.syncId } + preview)
            .sortedByDescending { it.createdAt }
        cachedLocalOrderDayKey = dayKey
        cachedLocalOrderPreviews = updated
        val payload = JSONArray().apply { updated.forEach { put(it.toJsonObject()) } }
        todayOrderPreviewsPrefs.edit()
            .putString("day", dayKey)
            .putString("orders", payload.toString())
            .commit()
    }

    private fun clearLocalOrderPreviews() {
        cachedLocalOrderDayKey = todayDayKey()
        cachedLocalOrderPreviews = emptyList()
        todayOrderPreviewsPrefs.edit().clear().commit()
    }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { optString(it, null) }

    private data class PendingOrder(
        val lines: List<OrderLine>,
        val sale: SaleRecord,
        val productSyncIds: Map<Long, String>,
        val orderCasheaLevel: CasheaCalculator.CasheaLevel? = null,
        val discountTicketCode: String? = null,
        val discountUsd: Double = 0.0
    )

    companion object {
        const val MAX_CLOSINGS_PER_DAY = 5
    }
}
