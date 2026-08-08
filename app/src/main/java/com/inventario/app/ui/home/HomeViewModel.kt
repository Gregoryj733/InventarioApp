package com.inventario.app.ui.home

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.inventario.app.data.bcv.BcvRateFetcher
import com.inventario.app.data.cashea.CasheaCalculator
import com.inventario.app.data.entity.ConfirmedOrderPreview
import com.inventario.app.data.entity.DiscountTicket
import com.inventario.app.data.entity.Product
import com.inventario.app.data.entity.UserRole
import com.inventario.app.data.entity.canManageDiscountTickets
import com.inventario.app.data.entity.canResetTodayOrders
import com.inventario.app.data.entity.isExpired
import com.inventario.app.data.entity.isUsed
import com.inventario.app.data.entity.isVoided
import com.inventario.app.data.order.OrderLine
import com.inventario.app.data.order.ProductPaymentChoice
import com.inventario.app.data.excel.ImportResult
import com.inventario.app.data.repository.InventoryRepository
import com.inventario.app.data.session.SessionManager
import com.inventario.app.data.sync.CloudConfigStore
import com.inventario.app.data.sync.CloudSyncInfo
import com.inventario.app.data.sync.CloudSyncStatus
import com.inventario.app.data.sync.SyncConfig
import com.inventario.app.data.sync.toUserMessage
import com.inventario.app.ui.theme.AppAlertController
import com.inventario.app.ui.theme.AppSnackbarController
import com.inventario.app.util.AppNotificationMessages
import com.inventario.app.util.AppNotifier
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ImportAlert(
    val title: String,
    val message: String,
    val isSuccess: Boolean
)

data class HomeUiState(
    val username: String = "",
    val role: UserRole = UserRole.CONSULTA,
    val query: String = "",
    val suggestions: List<String> = emptyList(),
    val results: List<Product> = emptyList(),
    val allProducts: List<Product> = emptyList(),
    val searching: Boolean = false,
    val currentDate: String = "",
    val bcvRate: Double? = null,
    val bcvLabel: String = "Tasa BCV: —",
    val bcvRefreshing: Boolean = false,
    val productCount: Int = 0,
    val importAlert: ImportAlert? = null,
    val importing: Boolean = false,
    val error: String? = null,
    val selectedProduct: Product? = null,
    val selectedQtyText: String = "1",
    val qtyWarning: String? = null,
    val selectedPaymentChoice: ProductPaymentChoice? = null,
    val orderLines: List<OrderLine> = emptyList(),
    val orderPaymentChoice: ProductPaymentChoice? = null,
    val showReceipt: Boolean = false,
    val orderProcessing: Boolean = false,
    val orderSuccessMessage: String? = null,
    val lastWhatsAppMessage: String? = null,
    val cloudSyncLabel: String = "Nube: conectando…",
    val cloudSyncDetail: String? = null,
    val showCloudConfigDialog: Boolean = false,
    val cloudConfigUrl: String = "",
    val cloudConfigApiKey: String = "",
    val cloudConfigMessage: String? = null,
    val confirmedOrdersToday: Int = 0,
    val resettingOrders: Boolean = false,
    val showConfirmedOrdersPreview: Boolean = false,
    val confirmedOrdersPreviewLoading: Boolean = false,
    val confirmedOrdersPreview: List<ConfirmedOrderPreview> = emptyList(),
    val confirmedOrdersPreviewError: String? = null,
    // ---- Ticket de descuento: canje en el carrito ----
    val discountTicketCodeInput: String = "",
    val validatingDiscountTicket: Boolean = false,
    val discountTicketError: String? = null,
    val appliedDiscountTicket: DiscountTicket? = null,
    // ---- Ticket de descuento: generación tras confirmar una venta ----
    val lastConfirmedSaleSyncId: String? = null,
    val showGenerateTicketDialog: Boolean = false,
    val generateTicketCustomerName: String = "",
    val generateTicketCustomerPhone: String = "",
    val generatingTicket: Boolean = false,
    val generateTicketError: String? = null,
    val generatedTicket: DiscountTicket? = null
)

class HomeViewModel(
    private val appContext: Context,
    private val inventoryRepository: InventoryRepository,
    private val sessionManager: SessionManager,
    private val bcvRateFetcher: BcvRateFetcher,
    private val restartCloudSync: () -> StateFlow<CloudSyncInfo>,
    private val appNotifier: AppNotifier,
    private val onOrderConfirmedSound: (String) -> Unit = {},
    private val onOrdersReset: () -> Unit = {}
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var cloudSyncJob: Job? = null
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale("es", "VE"))
    private val timeFormat = SimpleDateFormat("HH:mm", Locale("es", "VE"))
    private val moneyFormat = NumberFormat.getNumberInstance(Locale("es", "VE")).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 4
    }
    private val bcvRateFormat = NumberFormat.getNumberInstance(Locale("es", "VE")).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }

    init {
        _state.update {
            it.copy(
                username = sessionManager.username().orEmpty(),
                role = sessionManager.role() ?: UserRole.CONSULTA,
                currentDate = dateFormat.format(Date())
            )
        }
        viewModelScope.launch {
            inventoryRepository.observeMeta().collect { meta ->
                val rate = meta?.bcvRate?.let(::roundBcvRate)
                _state.update { state ->
                    state.copy(
                        bcvRate = rate,
                        bcvLabel = if (rate != null) {
                            "Tasa BCV: Bs ${bcvRateFormat.format(rate)}"
                        } else {
                            "Tasa BCV: sin datos"
                        }
                    )
                }
                if (BcvRateFetcher.isStale(meta?.bcvFetchedAt)) {
                    refreshBcv()
                }
            }
        }
        viewModelScope.launch {
            inventoryRepository.observeAllProducts().collect { products ->
                _state.update { state ->
                    state.copy(
                        allProducts = products,
                        productCount = products.size
                    )
                }
            }
        }
        viewModelScope.launch {
            // Lista completa en vivo: se actualiza sola con cada evento
            // "sales" del WebSocket (pedido propio o de otro dispositivo).
            inventoryRepository.observeConfirmedOrdersToday().collect { orders ->
                _state.update {
                    it.copy(
                        confirmedOrdersToday = orders.size,
                        confirmedOrdersPreview = orders,
                        confirmedOrdersPreviewLoading = false,
                        confirmedOrdersPreviewError = null
                    )
                }
            }
        }
        subscribeCloudSync()
        refreshBcv()
    }

    fun resetTodayOrders() {
        if (!_state.value.role.canResetTodayOrders()) {
            AppSnackbarController.show("No tienes permisos para reiniciar el contador.")
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(resettingOrders = true, error = null) }
            runCatching { inventoryRepository.resetTodayOrders() }
                .onSuccess {
                    _state.update {
                        it.copy(
                            resettingOrders = false,
                            confirmedOrdersToday = 0,
                            showConfirmedOrdersPreview = false,
                            confirmedOrdersPreview = emptyList(),
                            confirmedOrdersPreviewError = null
                        )
                    }
                    AppSnackbarController.show("Contador de pedidos del día reiniciado.")
                    onOrdersReset()
                }
                .onFailure { err ->
                    val message = err.toUserMessage("No se pudo reiniciar el contador.")
                    _state.update {
                        it.copy(
                            resettingOrders = false,
                            error = message
                        )
                    }
                    AppSnackbarController.show(message)
                }
        }
    }

    fun openConfirmedOrdersPreview() {
        if (_state.value.confirmedOrdersToday <= 0) return
        val cached = _state.value.confirmedOrdersPreview
        // Muestra de inmediato la caché en memoria (alimentada por el flow /
        // WebSocket) y solo pone loading si aún no hay detalle local.
        _state.update {
            it.copy(
                showConfirmedOrdersPreview = true,
                confirmedOrdersPreviewLoading = cached.isEmpty(),
                confirmedOrdersPreviewError = null
            )
        }
        viewModelScope.launch {
            runCatching { inventoryRepository.refreshConfirmedOrdersToday() }
                .onSuccess {
                    // El collect de observeConfirmedOrdersToday actualiza
                    // preview + loading=false; aquí cubrimos el caso en que
                    // el refresh no cambie el valor del flow (misma lista).
                    _state.update { it.copy(confirmedOrdersPreviewLoading = false) }
                }
                .onFailure { err ->
                    _state.update {
                        it.copy(
                            confirmedOrdersPreviewLoading = false,
                            confirmedOrdersPreviewError = if (cached.isEmpty()) {
                                err.toUserMessage("No se pudieron cargar los pedidos confirmados.")
                            } else {
                                null
                            }
                        )
                    }
                }
        }
    }

    fun dismissConfirmedOrdersPreview() {
        _state.update {
            it.copy(
                showConfirmedOrdersPreview = false,
                confirmedOrdersPreviewError = null
            )
        }
    }

    fun formatOrderTime(createdAt: Long): String = timeFormat.format(Date(createdAt))

    private fun subscribeCloudSync() {
        cloudSyncJob?.cancel()
        val syncFlow = inventoryRepository.observeCloudSyncStatus()
        cloudSyncJob = viewModelScope.launch {
            syncFlow.collect { info ->
                _state.update {
                    it.copy(
                        cloudSyncLabel = cloudSyncLabel(info),
                        cloudSyncDetail = cloudSyncDetail(info)
                    )
                }
            }
        }
    }

    fun displayProducts(): List<Product> {
        val state = _state.value
        return if (state.query.trim().isEmpty()) state.allProducts else state.results
    }

    private fun cloudSyncLabel(info: CloudSyncInfo): String = when (info.status) {
        CloudSyncStatus.IDLE -> "Nube: conectando…"
        CloudSyncStatus.SYNCING -> "Nube: sincronizando…"
        CloudSyncStatus.SYNCED -> "Nube: sincronizado"
        CloudSyncStatus.OFFLINE -> "Nube: sin conexión"
        CloudSyncStatus.ERROR -> when {
            info.detail?.contains("Permiso denegado", ignoreCase = true) == true ->
                "Nube: permiso denegado"
            info.detail?.contains("Clave API", ignoreCase = true) == true ->
                "Nube: clave API inválida"
            info.detail?.contains("no encontrado", ignoreCase = true) == true ->
                "Nube: servidor no encontrado"
            info.detail?.contains("internet", ignoreCase = true) == true ||
                info.detail?.contains("conexión", ignoreCase = true) == true ||
                info.detail?.contains("inaccesible", ignoreCase = true) == true ->
                "Nube: sin conexión"
            info.detail?.contains("HTTP 502", ignoreCase = true) == true ||
                info.detail?.contains("HTTP 503", ignoreCase = true) == true ||
                info.detail?.contains("HTTP 504", ignoreCase = true) == true ||
                info.detail?.contains("no responde", ignoreCase = true) == true ||
                info.detail?.contains("Servidor iniciando", ignoreCase = true) == true ->
                "Nube: servidor iniciando"
            info.detail?.contains("pendiente", ignoreCase = true) == true ||
                info.detail?.contains("subida pendiente", ignoreCase = true) == true ->
                "Nube: subida pendiente"
            info.detail?.contains("sync_config", ignoreCase = true) == true ->
                "Nube: servidor no configurado"
            else -> "Nube: error de sincronización"
        }
    }

    private fun cloudSyncDetail(info: CloudSyncInfo): String? = when (info.status) {
        CloudSyncStatus.ERROR, CloudSyncStatus.OFFLINE -> info.detail
        CloudSyncStatus.SYNCED -> info.detail?.takeIf {
            it.contains("pendiente", ignoreCase = true) ||
                it.contains("iniciando", ignoreCase = true)
        }
        else -> null
    }

    fun onQueryChange(value: String) {
        _state.update {
            it.copy(
                query = value,
                error = null,
                importAlert = null,
                orderSuccessMessage = null,
                selectedProduct = if (value != it.query) null else it.selectedProduct,
                qtyWarning = null
            )
        }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300)
            val q = value.trim()
            if (q.isEmpty()) {
                _state.update { it.copy(suggestions = emptyList(), results = emptyList(), searching = false) }
                return@launch
            }
            _state.update { it.copy(searching = true) }
            val results = inventoryRepository.search(q)
            val suggestions = results.map { it.description }.distinct().take(12)
            _state.update {
                it.copy(
                    suggestions = suggestions,
                    results = results,
                    searching = false
                )
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _state.update {
            it.copy(
                query = "",
                suggestions = emptyList(),
                results = emptyList(),
                searching = false,
                selectedProduct = null,
                selectedQtyText = "1",
                qtyWarning = null,
                error = null
            )
        }
    }

    fun selectSuggestion(text: String) {
        _state.update { it.copy(query = text, suggestions = emptyList(), selectedProduct = null) }
        viewModelScope.launch {
            val results = inventoryRepository.search(text)
            _state.update { it.copy(results = results) }
        }
    }

    fun runSearch() {
        val q = _state.value.query.trim()
        if (q.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(searching = true, suggestions = emptyList(), selectedProduct = null) }
            val results = inventoryRepository.search(q)
            _state.update { it.copy(results = results, searching = false) }
        }
    }

    fun selectProduct(product: Product) {
        _state.update {
            it.copy(
                selectedProduct = product,
                selectedQtyText = "1",
                qtyWarning = null,
                selectedPaymentChoice = null,
                suggestions = emptyList()
            )
        }
    }

    fun clearSelection() {
        _state.update {
            it.copy(
                selectedProduct = null,
                selectedQtyText = "1",
                qtyWarning = null,
                selectedPaymentChoice = null
            )
        }
    }

    fun selectPagoMovil() {
        _state.update { it.copy(selectedPaymentChoice = ProductPaymentChoice.PagoMovil) }
    }

    fun selectCasheaLevel(level: CasheaCalculator.CasheaLevel) {
        _state.update { it.copy(selectedPaymentChoice = ProductPaymentChoice.Cashea(level)) }
    }

    fun casheaSelectionWarning(): String? {
        if (!CasheaCalculator.isCasheaEligible(lineTotalUsd())) return null
        if (_state.value.selectedPaymentChoice != null) return null
        return "Seleccione forma de pago: Cashea o Pago móvil / Punto."
    }

    fun canConfirmOrder(): Boolean = _state.value.orderLines.isNotEmpty()

    fun selectedLineCasheaDetail(): CasheaCalculator.CasheaLineDetail? {
        val choice = _state.value.selectedPaymentChoice as? ProductPaymentChoice.Cashea ?: return null
        val rate = _state.value.bcvRate ?: return null
        return CasheaCalculator.lineDetail(lineTotalUsd(), rate, choice.level)
    }

    fun lineCasheaSimulation(line: OrderLine): CasheaCalculator.CasheaSimulation? {
        val rate = _state.value.bcvRate ?: return null
        if (line.totalUsd <= 0) return null
        return CasheaCalculator.simulate(
            baseUsd = line.totalUsd,
            rate = rate,
            quantity = line.quantity
        )
    }

    fun lineCasheaDetail(line: OrderLine): CasheaCalculator.CasheaLineDetail? {
        val level = line.casheaLevel ?: return null
        val rate = _state.value.bcvRate ?: return null
        return CasheaCalculator.lineDetail(line.totalUsd, rate, level)
    }

    fun onSelectedQtyChange(raw: String) {
        val cleaned = raw.filter { it.isDigit() || it == '.' || it == ',' }
            .replace(',', '.')
            .let { text ->
                val parts = text.split('.')
                if (parts.size <= 1) text
                else parts.first() + "." + parts.drop(1).joinToString("").take(4)
            }

        val product = _state.value.selectedProduct
        val qty = cleaned.toDoubleOrNull()
        val orderQty = orderQtyForProduct(product?.id)
        val available = product?.quantity?.minus(orderQty) ?: 0.0

        val warning = when {
            cleaned.isBlank() -> null
            qty == null -> "Cantidad inválida."
            qty <= 0 -> "La cantidad debe ser mayor a 0."
            product != null && qty > available ->
                "Supera el stock disponible (${formatQty(available)} ${product.unit})."
            else -> null
        }

        _state.update {
            it.copy(
                selectedQtyText = cleaned,
                qtyWarning = warning,
                selectedPaymentChoice = if (
                    product != null &&
                    qty != null &&
                    qty > 0 &&
                    CasheaCalculator.isCasheaEligible(product.price * qty)
                ) {
                    it.selectedPaymentChoice
                } else {
                    null
                }
            )
        }
    }

    private fun orderQtyForProduct(productId: Long?): Double {
        if (productId == null) return 0.0
        return _state.value.orderLines
            .filter { it.productId == productId }
            .sumOf { it.quantity }
    }

    fun selectedQtyValue(): Double =
        _state.value.selectedQtyText.toDoubleOrNull()?.takeIf { it > 0 } ?: 0.0

    fun lineTotalUsd(): Double {
        val product = _state.value.selectedProduct ?: return 0.0
        return product.price * selectedQtyValue()
    }

    fun lineTotalBs(): Double? {
        val rate = _state.value.bcvRate ?: return null
        return lineTotalUsd() * rate
    }

    fun canAddToOrder(): Boolean {
        val state = _state.value
        val product = state.selectedProduct ?: return false
        val qty = selectedQtyValue()
        return qty > 0 &&
            state.qtyWarning == null &&
            qty <= product.quantity - orderQtyForProduct(product.id)
    }

    private fun notifyPopup(title: String, message: String) {
        AppAlertController.show(title, message)
    }

    private fun notifyPopupWithSound(dedupeKey: String, title: String, message: String) {
        appNotifier.notify(dedupeKey, title, message)
    }

    fun addToOrder() {
        if (!canAddToOrder()) return
        val product = _state.value.selectedProduct ?: return
        val qty = selectedQtyValue()
        val line = OrderLine(
            productId = product.id,
            description = product.description,
            unit = product.unit,
            unitPriceUsd = product.price,
            quantity = qty,
            casheaLevel = null
        )
        val existing = _state.value.orderLines.filter { it.productId != product.id }
        _state.update {
            it.copy(
                orderLines = existing + line,
                selectedProduct = null,
                selectedQtyText = "1",
                qtyWarning = null,
                selectedPaymentChoice = null,
                orderSuccessMessage = null,
                error = null
            )
        }
        revalidateOrderCasheaEligibility()
        AppSnackbarController.show("\"${product.description}\" agregado al pedido.")
    }

    fun isOrderCasheaEligible(): Boolean =
        CasheaCalculator.isCasheaEligible(orderTotalUsd())

    fun paymentChoiceForOrder(): ProductPaymentChoice? {
        if (!isOrderCasheaEligible()) return null
        return _state.value.orderPaymentChoice ?: ProductPaymentChoice.PagoMovil
    }

    fun selectOrderPagoMovil() {
        if (!isOrderCasheaEligible()) return
        _state.update { it.copy(orderPaymentChoice = ProductPaymentChoice.PagoMovil) }
        notifyOrderPaymentUpdated("Pago móvil / Punto")
    }

    fun selectOrderCasheaLevel(level: CasheaCalculator.CasheaLevel) {
        if (!isOrderCasheaEligible()) return
        _state.update { it.copy(orderPaymentChoice = ProductPaymentChoice.Cashea(level)) }
        notifyOrderPaymentUpdated("Cashea ${level.label}")
    }

    fun orderCasheaDetail(): CasheaCalculator.CasheaLineDetail? {
        val choice = _state.value.orderPaymentChoice as? ProductPaymentChoice.Cashea ?: return null
        val rate = _state.value.bcvRate ?: return null
        return CasheaCalculator.lineDetail(orderTotalUsd(), rate, choice.level)
    }

    fun orderCasheaLevelForSync(): CasheaCalculator.CasheaLevel? {
        if (!isOrderCasheaEligible()) return null
        return (_state.value.orderPaymentChoice as? ProductPaymentChoice.Cashea)?.level
    }

    private fun notifyOrderPaymentUpdated(label: String) {
        val dedupeKey = "order_payment_${label.hashCode()}"
        val (title, message) = AppNotificationMessages.paymentChoiceUpdated(label)
        notifyPopupWithSound(dedupeKey, title, message)
    }

    private fun revalidateOrderCasheaEligibility() {
        _state.update { state ->
            if (CasheaCalculator.isCasheaEligible(state.orderLines.sumOf { it.totalUsd })) {
                state
            } else {
                state.copy(orderPaymentChoice = null)
            }
        }
    }

    fun removeOrderLine(productId: Long) {
        val removed = _state.value.orderLines.find { it.productId == productId }
        _state.update {
            it.copy(orderLines = it.orderLines.filter { line -> line.productId != productId })
        }
        revalidateOrderCasheaEligibility()
        if (removed != null) {
            AppSnackbarController.show("\"${removed.description}\" quitado del pedido.")
        }
    }

    fun editOrderLine(productId: Long) {
        val line = _state.value.orderLines.find { it.productId == productId } ?: return
        val product = _state.value.allProducts.find { it.id == productId }
            ?: _state.value.results.find { it.id == productId }
            ?: return
        _state.update {
            it.copy(
                orderLines = it.orderLines.filter { orderLine -> orderLine.productId != productId },
                selectedProduct = product,
                selectedQtyText = formatQty(line.quantity),
                qtyWarning = null,
                selectedPaymentChoice = null,
                error = null
            )
        }
        revalidateOrderCasheaEligibility()
        AppSnackbarController.show("Editando \"${line.description}\". Ajuste cantidad o forma de pago.")
    }

    fun clearOrder() {
        _state.update {
            it.copy(
                orderLines = emptyList(),
                showReceipt = false,
                selectedPaymentChoice = null,
                orderPaymentChoice = null,
                appliedDiscountTicket = null,
                discountTicketCodeInput = "",
                discountTicketError = null
            )
        }
        AppSnackbarController.show("Pedido vaciado.")
    }

    fun orderTotalUsd(): Double = _state.value.orderLines.sumOf { it.totalUsd }

    fun orderTotalBs(): Double? {
        val rate = _state.value.bcvRate ?: return null
        return orderTotalUsd() * rate
    }

    /** Monto del descuento (USD) del ticket aplicado al carrito, si hay uno. */
    fun discountAmountUsd(): Double {
        val ticket = _state.value.appliedDiscountTicket ?: return 0.0
        return orderTotalUsd() * ticket.discountPercent / 100.0
    }

    fun orderTotalUsdAfterDiscount(): Double = (orderTotalUsd() - discountAmountUsd()).coerceAtLeast(0.0)

    fun orderTotalBsAfterDiscount(): Double? {
        val rate = _state.value.bcvRate ?: return null
        return orderTotalUsdAfterDiscount() * rate
    }

    fun formatTicketDate(millis: Long): String = dateFormat.format(Date(millis))

    fun onDiscountTicketCodeChange(value: String) {
        _state.update { it.copy(discountTicketCodeInput = value.uppercase(), discountTicketError = null) }
    }

    /** Valida un código (escrito o escaneado) contra el servidor antes de aplicarlo al carrito. */
    fun applyDiscountTicket(code: String = _state.value.discountTicketCodeInput) {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) {
            _state.update { it.copy(discountTicketError = "Ingresa o escanea un código.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(validatingDiscountTicket = true, discountTicketError = null) }
            inventoryRepository.findDiscountTicket(trimmed)
                .onSuccess { ticket ->
                    val errorMessage = when {
                        ticket == null -> "Ticket no encontrado. Verifica el código."
                        ticket.isVoided() -> "Este ticket fue anulado."
                        ticket.isUsed() -> "Este ticket ya fue utilizado."
                        ticket.isExpired() -> "Este ticket expiró el ${formatTicketDate(ticket.expiresAt)}."
                        else -> null
                    }
                    if (errorMessage != null) {
                        _state.update {
                            it.copy(validatingDiscountTicket = false, discountTicketError = errorMessage)
                        }
                    } else {
                        _state.update {
                            it.copy(
                                validatingDiscountTicket = false,
                                discountTicketError = null,
                                appliedDiscountTicket = ticket,
                                discountTicketCodeInput = ""
                            )
                        }
                        AppSnackbarController.show(
                            "Descuento de ${formatQty(ticket!!.discountPercent)}% aplicado (${ticket.customerName})."
                        )
                    }
                }
                .onFailure { err ->
                    _state.update {
                        it.copy(
                            validatingDiscountTicket = false,
                            discountTicketError = err.toUserMessage("No se pudo validar el ticket.")
                        )
                    }
                }
        }
    }

    /** Código leído desde el escáner de QR (ScanContract de ZXing): igual flujo que escribirlo a mano. */
    fun onDiscountTicketScanned(rawText: String) {
        val code = rawText.trim()
        if (code.isEmpty()) return
        _state.update { it.copy(discountTicketCodeInput = code.uppercase()) }
        applyDiscountTicket(code)
    }

    fun removeDiscountTicket() {
        _state.update { it.copy(appliedDiscountTicket = null, discountTicketError = null) }
    }

    fun openGenerateTicketDialog() {
        _state.update {
            it.copy(
                showGenerateTicketDialog = true,
                generateTicketCustomerName = "",
                generateTicketCustomerPhone = "",
                generateTicketError = null
            )
        }
    }

    fun dismissGenerateTicketDialog() {
        _state.update { it.copy(showGenerateTicketDialog = false, generateTicketError = null) }
    }

    fun dismissDiscountTicketOffer() {
        _state.update { it.copy(lastConfirmedSaleSyncId = null) }
    }

    fun onGenerateTicketNameChange(value: String) {
        _state.update { it.copy(generateTicketCustomerName = value, generateTicketError = null) }
    }

    fun onGenerateTicketPhoneChange(value: String) {
        _state.update { it.copy(generateTicketCustomerPhone = value, generateTicketError = null) }
    }

    fun submitGenerateTicket() {
        if (!_state.value.role.canManageDiscountTickets()) {
            _state.update { it.copy(generateTicketError = "No tienes permisos para generar tickets.") }
            return
        }
        val name = _state.value.generateTicketCustomerName.trim()
        val phone = _state.value.generateTicketCustomerPhone.trim()
        if (name.isEmpty()) {
            _state.update { it.copy(generateTicketError = "Ingresa el nombre del cliente.") }
            return
        }
        if (phone.isEmpty()) {
            _state.update { it.copy(generateTicketError = "Ingresa el teléfono del cliente.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(generatingTicket = true, generateTicketError = null) }
            inventoryRepository.issueDiscountTicket(
                customerName = name,
                customerPhone = phone,
                sourceSaleSyncId = _state.value.lastConfirmedSaleSyncId
            ).onSuccess { ticket ->
                _state.update {
                    it.copy(
                        generatingTicket = false,
                        showGenerateTicketDialog = false,
                        generatedTicket = ticket,
                        lastConfirmedSaleSyncId = null
                    )
                }
                AppSnackbarController.show("Ticket ${ticket.code} generado para ${ticket.customerName}.")
            }.onFailure { err ->
                _state.update {
                    it.copy(
                        generatingTicket = false,
                        generateTicketError = err.toUserMessage("No se pudo generar el ticket.")
                    )
                }
            }
        }
    }

    fun dismissGeneratedTicket() {
        _state.update { it.copy(generatedTicket = null) }
    }

    fun generatedTicketShareText(): String? {
        val ticket = _state.value.generatedTicket ?: return null
        return buildString {
            appendLine("Código de descuento Total Care")
            appendLine("Cliente: ${ticket.customerName}")
            appendLine("Teléfono: ${ticket.customerPhone}")
            appendLine("Código: ${ticket.code}")
            appendLine("Descuento: ${formatQty(ticket.discountPercent)}%")
            appendLine("Válido hasta: ${formatTicketDate(ticket.expiresAt)}")
            appendLine()
            append(com.inventario.app.data.entity.DISCOUNT_TICKET_CONDITIONS)
        }
    }

    fun showOrderReceipt() {
        if (_state.value.orderLines.isEmpty() || !canConfirmOrder()) return
        _state.update { it.copy(showReceipt = true, error = null) }
    }

    fun dismissReceipt() {
        _state.update { it.copy(showReceipt = false) }
    }

    fun buildWhatsAppMessage(): String {
        val state = _state.value
        val now = Date()
        val lines = state.orderLines
        val subtotalUsd = orderTotalUsd()
        val ticket = state.appliedDiscountTicket
        val discountUsd = discountAmountUsd()
        val totalUsd = orderTotalUsdAfterDiscount()
        val totalBs = orderTotalBsAfterDiscount()
        val bcv = state.bcvRate

        return buildString {
            appendLine("================================")
            appendLine("*PEDIDO — TOTAL CARE AUTOMOTRIZ*")
            appendLine("================================")
            appendLine()
            appendLine("Usuario: ${state.username}")
            appendLine("Fecha: ${dateFormat.format(now)}  ${timeFormat.format(now)}")
            if (bcv != null) {
                appendLine("Tasa BCV: Bs ${bcvRateFormat.format(bcv)}")
            }
            appendLine()
            appendLine("--- DETALLE ---")
            lines.forEachIndexed { index, line ->
                appendLine()
                appendLine("${index + 1}. *${line.description}*")
                appendLine("   ${formatQty(line.quantity)} ${line.unit} x ${formatPrice(line.unitPriceUsd)}")
                append("   Subtotal: ${formatPrice(line.totalUsd)}")
                if (bcv != null) {
                    append(" | Bs ${moneyFormat.format(line.totalUsd * bcv)}")
                }
                appendLine()
            }
            appendLine()
            appendLine("--- TOTALES ---")
            if (ticket != null) {
                appendLine("Subtotal USD: ${formatPrice(subtotalUsd)}")
                appendLine(
                    "Descuento (${ticket.customerName} · -${formatQty(ticket.discountPercent)}%): " +
                        "-${formatPrice(discountUsd)}"
                )
            }
            appendLine("*Total USD: ${formatPrice(totalUsd)}*")
            if (totalBs != null) {
                appendLine("*Total Bs: Bs ${moneyFormat.format(totalBs)}*")
            }
            orderCasheaDetail()?.let { casheaDetail ->
                appendLine()
                appendLine("*Forma de pago: Cashea ${casheaDetail.level.label}*")
                appendLine(
                    "Pago inicial: ${formatPrice(casheaDetail.initialUsd)} | " +
                        "Bs ${moneyFormat.format(casheaDetail.initialBs)}"
                )
                appendLine(
                    "Pendiente en ${casheaDetail.installmentCount} cuotas (${casheaDetail.pendingPercent}%): " +
                        "${formatPrice(casheaDetail.pendingUsd)} | Bs ${moneyFormat.format(casheaDetail.pendingBs)}"
                )
            }
            if (orderCasheaDetail() == null && isOrderCasheaEligible()) {
                appendLine()
                appendLine("*Forma de pago: Pago móvil / Punto*")
            }
            appendLine()
            appendLine("Stock actualizado en inventario.")
        }
    }

    fun confirmOrder(onWhatsApp: (String) -> Unit) {
        val lines = _state.value.orderLines
        if (lines.isEmpty()) return

        viewModelScope.launch {
            _state.update { it.copy(orderProcessing = true, error = null) }
            val discountTicket = _state.value.appliedDiscountTicket
            val result = inventoryRepository.executeOrder(
                lines = lines,
                orderCasheaLevel = orderCasheaLevelForSync(),
                discountTicket = discountTicket
            )
            result.onSuccess { syncId ->
                val message = buildWhatsAppMessage()
                val count = inventoryRepository.productCount()
                val q = _state.value.query
                val results = if (q.trim().isNotEmpty()) inventoryRepository.search(q) else emptyList()
                _state.update {
                    it.copy(
                        orderProcessing = false,
                        showReceipt = false,
                        orderLines = emptyList(),
                        orderSuccessMessage = "Pedido registrado. Stock actualizado.",
                        lastConfirmedSaleSyncId = syncId,
                        lastWhatsAppMessage = message,
                        productCount = count,
                        results = results,
                        selectedProduct = null,
                        selectedQtyText = "1",
                        selectedPaymentChoice = null,
                        orderPaymentChoice = null,
                        appliedDiscountTicket = null,
                        discountTicketCodeInput = "",
                        discountTicketError = null
                    )
                }
                onOrderConfirmedSound(syncId)
                onWhatsApp(message)
            }.onFailure { err ->
                val message = err.toUserMessage("No se pudo completar el pedido.")
                _state.update {
                    it.copy(
                        orderProcessing = false,
                        error = message
                    )
                }
                notifyPopup("Error al confirmar pedido", message)
            }
        }
    }

    fun refreshBcv() {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    bcvRefreshing = true,
                    currentDate = dateFormat.format(Date())
                )
            }
            val result = bcvRateFetcher.fetchUsdRate()
            result.onSuccess { rate ->
                val rounded = roundBcvRate(rate)
                inventoryRepository.saveBcvRate(rounded)
                _state.update {
                    it.copy(
                        bcvRefreshing = false,
                        bcvRate = rounded,
                        bcvLabel = "Tasa BCV: Bs ${bcvRateFormat.format(rounded)}"
                    )
                }
            }.onFailure {
                // Sin mensaje técnico: se mantiene la última tasa guardada en observeMeta()
                _state.update { it.copy(bcvRefreshing = false) }
            }
        }
    }

    fun importExcel(uri: Uri) {
        if (_state.value.role != UserRole.ADMIN) {
            _state.update { it.copy(error = "Solo el administrador puede actualizar el inventario.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(importing = true, importAlert = null, error = null) }
            val result = inventoryRepository.replaceInventoryFromExcel(uri)
            val count = inventoryRepository.productCount()
            val alert = buildImportAlert(result, count)
            AppSnackbarController.show(alert.title)
            val q = _state.value.query
            val results = if (q.trim().isNotEmpty()) inventoryRepository.search(q) else emptyList()
            val selectedId = _state.value.selectedProduct?.id
            val refreshedSelected = selectedId?.let { id -> results.find { it.id == id } }
            _state.update {
                it.copy(
                    importing = false,
                    importAlert = alert,
                    productCount = count,
                    results = results,
                    suggestions = emptyList(),
                    selectedProduct = refreshedSelected,
                    selectedQtyText = if (refreshedSelected == null) "1" else it.selectedQtyText,
                    orderLines = emptyList()
                )
            }
        }
    }

    fun dismissImportAlert() {
        _state.update { it.copy(importAlert = null) }
    }

    private fun buildImportAlert(result: ImportResult, totalProducts: Int): ImportAlert {
        val details = buildString {
            append("Productos en inventario: $totalProducts")
            append("\nImportados: ${result.imported}")
            if (result.skipped > 0) {
                append("\nOmitidos: ${result.skipped}")
            }
            if (result.errors.isNotEmpty()) {
                append("\n\n")
                append(result.errors.joinToString("\n"))
            }
        }
        return when {
            result.imported > 0 -> ImportAlert(
                title = "Inventario actualizado",
                message = "El archivo Excel se cargó correctamente.\n\n$details",
                isSuccess = true
            )
            result.errors.isNotEmpty() -> ImportAlert(
                title = "No se pudo cargar el inventario",
                message = details,
                isSuccess = false
            )
            else -> ImportAlert(
                title = "Archivo sin productos",
                message = "El archivo no contenía productos válidos para importar.",
                isSuccess = false
            )
        }
    }

    fun clearWhatsAppFollowUp() {
        _state.update {
            it.copy(orderSuccessMessage = null, lastWhatsAppMessage = null)
        }
    }

    fun logout(onDone: () -> Unit) {
        sessionManager.clear()
        onDone()
    }

    fun openCloudConfigDialog() {
        val current = SyncConfig.load(appContext)
        _state.update {
            it.copy(
                showCloudConfigDialog = true,
                cloudConfigUrl = current?.baseUrl.orEmpty(),
                cloudConfigApiKey = current?.apiKey.orEmpty(),
                cloudConfigMessage = null
            )
        }
    }

    fun dismissCloudConfigDialog() {
        _state.update {
            it.copy(
                showCloudConfigDialog = false,
                cloudConfigUrl = "",
                cloudConfigApiKey = "",
                cloudConfigMessage = null
            )
        }
    }

    fun onCloudConfigUrlChange(value: String) {
        _state.update { it.copy(cloudConfigUrl = value, cloudConfigMessage = null) }
    }

    fun onCloudConfigApiKeyChange(value: String) {
        _state.update { it.copy(cloudConfigApiKey = value, cloudConfigMessage = null) }
    }

    fun saveCloudConfig() {
        val url = _state.value.cloudConfigUrl.trim().trimEnd('/')
        val apiKey = _state.value.cloudConfigApiKey.trim()
        if (url.isEmpty()) {
            _state.update { it.copy(cloudConfigMessage = "Ingresa la URL del servidor de sincronización.") }
            return
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            _state.update { it.copy(cloudConfigMessage = "La URL debe comenzar con http:// o https://") }
            return
        }
        val config = SyncConfig(baseUrl = url, apiKey = apiKey)
        CloudConfigStore.save(appContext, config)
        _state.update {
            it.copy(
                showCloudConfigDialog = false,
                cloudConfigUrl = "",
                cloudConfigApiKey = "",
                cloudConfigMessage = null,
                cloudSyncLabel = "Nube: conectando…",
                cloudSyncDetail = null
            )
        }
        restartCloudSync()
        subscribeCloudSync()
        AppSnackbarController.show("Configuración de sincronización guardada.")
    }

    fun needsCloudConfigButton(): Boolean {
        val label = _state.value.cloudSyncLabel
        return label.contains("no configurad", ignoreCase = true) ||
            label.contains("no encontrado", ignoreCase = true) ||
            label.contains("clave API", ignoreCase = true) ||
            label.contains("error de sincronización", ignoreCase = true)
    }

    fun isCloudSyncUnconfigured(): Boolean =
        _state.value.cloudSyncLabel.contains("no configurada", ignoreCase = true)

    private fun roundBcvRate(rate: Double): Double =
        kotlin.math.round(rate * 100) / 100.0

    fun formatPrice(value: Double): String = "$${moneyFormat.format(value)}"

    fun formatQty(value: Double): String {
        return if (value % 1.0 == 0.0) value.toInt().toString() else moneyFormat.format(value)
    }

    fun formatMoney(value: Double): String = moneyFormat.format(value)

    fun bsEquivalent(priceUsd: Double): String? {
        val rate = _state.value.bcvRate ?: return null
        return "Bs ${moneyFormat.format(priceUsd * rate)}"
    }

    fun casheaSimulation(): CasheaCalculator.CasheaSimulation? {
        val rate = _state.value.bcvRate ?: return null
        val baseUsd = lineTotalUsd()
        if (baseUsd <= 0) return null
        return CasheaCalculator.simulate(
            baseUsd = baseUsd,
            rate = rate,
            quantity = selectedQtyValue()
        )
    }

    fun orderCasheaSimulation(): CasheaCalculator.CasheaSimulation? {
        val rate = _state.value.bcvRate ?: return null
        val baseUsd = orderTotalUsd()
        if (!CasheaCalculator.isCasheaEligible(baseUsd)) return null
        return CasheaCalculator.simulate(
            baseUsd = baseUsd,
            rate = rate,
            quantity = 1.0
        )
    }

    companion object {
        fun factory(
            appContext: Context,
            inventoryRepository: InventoryRepository,
            sessionManager: SessionManager,
            bcvRateFetcher: BcvRateFetcher,
            restartCloudSync: () -> StateFlow<CloudSyncInfo>,
            appNotifier: AppNotifier,
            onOrderConfirmedSound: (String) -> Unit = {},
            onOrdersReset: () -> Unit = {}
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return HomeViewModel(
                    appContext,
                    inventoryRepository,
                    sessionManager,
                    bcvRateFetcher,
                    restartCloudSync,
                    appNotifier,
                    onOrderConfirmedSound,
                    onOrdersReset
                ) as T
            }
        }
    }
}
