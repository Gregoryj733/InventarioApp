package com.inventario.app.ui.cashclosing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.inventario.app.data.bcv.BcvRateFetcher
import com.inventario.app.data.entity.CashClosingAlertType
import com.inventario.app.data.entity.CashClosingRecord
import com.inventario.app.data.entity.CashClosingSnapshot
import com.inventario.app.data.entity.CashClosingSnapshotCodec
import com.inventario.app.data.entity.CashClosingStatus
import com.inventario.app.data.entity.ConfirmedOrderPreview
import com.inventario.app.data.entity.UserRole
import com.inventario.app.data.entity.canResetTodayOrders
import com.inventario.app.data.repository.InventoryRepository
import com.inventario.app.data.session.SessionManager
import com.inventario.app.data.sync.CloudEvent
import com.inventario.app.data.sync.toUserMessage
import com.inventario.app.ui.theme.AppSnackbarController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

data class PosEntry(
    val id: Long,
    val name: String,
    val usdText: String = "",
    val bsText: String = ""
)

data class MobilePaymentEntry(
    val id: Long,
    val ref: String,
    val usdText: String = "",
    val bsText: String = ""
)

data class ExpenseEntry(
    val id: Long,
    val description: String,
    val usdText: String = ""
)

data class CashEntry(
    val id: Long,
    val description: String,
    val usdText: String = "",
    val bsText: String = ""
)

data class CashClosingUiState(
    val branchName: String = "",
    val dateText: String = "",
    val rateText: String = "",
    val prevCashUsdText: String = "",
    val prevCashBsText: String = "",
    val salesUsdText: String = "",
    val salesBsText: String = "",
    val posEntries: List<PosEntry> = emptyList(),
    val mobileEntries: List<MobilePaymentEntry> = emptyList(),
    val cashEntries: List<CashEntry> = emptyList(),
    val casheaUsdText: String = "",
    val expenseEntries: List<ExpenseEntry> = emptyList(),
    val observations: String = "",
    val username: String = "",
    val loadingSales: Boolean = true,
    val bcvRate: Double? = null,
    val bcvLabel: String = "Tasa BCV: —",
    val bcvRefreshing: Boolean = false,
    val confirmedOrdersToday: Int = 0,
    val resettingOrders: Boolean = false,
    val canResetTodayOrders: Boolean = false,
    val showConfirmedOrdersPreview: Boolean = false,
    val confirmedOrdersPreview: List<ConfirmedOrderPreview> = emptyList(),
    val saveError: String? = null,
    val saveSuccess: Boolean = false,
    val closingAlert: CashClosingAlertType? = null,
    val remainingAttempts: Int = 5
)

class CashClosingViewModel(
    private val inventoryRepository: InventoryRepository,
    private val sessionManager: SessionManager,
    private val bcvRateFetcher: BcvRateFetcher,
    private val onClosingSubmittedSound: () -> Unit = {}
) : ViewModel() {

    private val _state = MutableStateFlow(CashClosingUiState())
    val state: StateFlow<CashClosingUiState> = _state.asStateFlow()

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

    private var nextId = 1L

    init {
        val userSucursal = sessionManager.sucursal()
        val role = sessionManager.role() ?: UserRole.CONSULTA
        _state.update {
            it.copy(
                username = sessionManager.username().orEmpty(),
                dateText = dateFormat.format(Date()),
                posEntries = listOf(newPosEntry("Punto 1")),
                cashEntries = listOf(newCashEntry("")),
                branchName = userSucursal.ifBlank { it.branchName },
                canResetTodayOrders = role.canResetTodayOrders()
            )
        }
        viewModelScope.launch {
            inventoryRepository.observeMeta().collect { meta ->
                val rate = meta?.bcvRate?.let(::roundRate)
                onBcvRateAvailable(rate, forceRateText = false)
                if (BcvRateFetcher.isStale(meta?.bcvFetchedAt)) {
                    refreshBcv()
                }
            }
        }
        viewModelScope.launch {
            val salesUsd = inventoryRepository.totalSalesToday()
            _state.update {
                it.copy(
                    loadingSales = false,
                    salesUsdText = if (salesUsd > 0) formatDecimal(salesUsd) else ""
                )
            }
            val rate = currentRate()
            if (rate != null && salesUsd > 0) {
                _state.update {
                    it.copy(salesBsText = formatDecimal(salesUsd * rate))
                }
            }
        }
        viewModelScope.launch {
            // Lista completa en vivo: se actualiza sola cuando cualquier
            // dispositivo confirma o reinicia los pedidos del día.
            inventoryRepository.observeConfirmedOrdersToday().collect { orders ->
                _state.update {
                    it.copy(
                        confirmedOrdersToday = orders.size,
                        confirmedOrdersPreview = orders
                    )
                }
            }
        }
        viewModelScope.launch {
            // El estado de aprobación de cierres (aceptado/rechazado por un
            // Admin o Supervisor desde otro dispositivo) también debe verse
            // sin recargar la pantalla.
            inventoryRepository.observeCloudEvents().collect { event ->
                if (event is CloudEvent.CashClosings) {
                    refreshClosingStatus()
                }
            }
        }
        refreshBcv()
        refreshClosingStatus()
    }

    fun refreshClosingStatus() {
        viewModelScope.launch {
            val username = sessionManager.username().orEmpty()
            val alert = inventoryRepository.cashClosingAlertForUser(username)
            val maxRevision = inventoryRepository.maxRevisionToday(username)
            val latest = inventoryRepository.latestClosingToday(username)
            val ackId = sessionManager.lastAcknowledgedClosingId(username)
            val visibleAlert = when {
                alert == CashClosingAlertType.REJECTED_RESUBMIT &&
                    latest?.status == CashClosingStatus.REJECTED &&
                    latest.id > ackId -> CashClosingAlertType.REJECTED_RESUBMIT
                alert == CashClosingAlertType.APPROVED_SUCCESS &&
                    latest?.status == CashClosingStatus.APPROVED &&
                    latest.id > ackId -> CashClosingAlertType.APPROVED_SUCCESS
                else -> null
            }
            _state.update {
                it.copy(
                    closingAlert = visibleAlert,
                    remainingAttempts = InventoryRepository.MAX_CLOSINGS_PER_DAY - maxRevision
                )
            }
        }
    }

    fun acknowledgeClosingAlert() {
        viewModelScope.launch {
            val username = sessionManager.username().orEmpty()
            val latest = inventoryRepository.latestClosingToday(username)
            if (latest != null) {
                sessionManager.acknowledgeClosing(username, latest.id)
            }
            _state.update { it.copy(closingAlert = null) }
        }
    }

    fun openConfirmedOrdersPreview() {
        if (_state.value.confirmedOrdersToday <= 0) return
        _state.update { it.copy(showConfirmedOrdersPreview = true) }
        viewModelScope.launch {
            runCatching { inventoryRepository.refreshConfirmedOrdersToday() }
        }
    }

    fun dismissConfirmedOrdersPreview() {
        _state.update { it.copy(showConfirmedOrdersPreview = false) }
    }

    fun formatOrderTime(createdAt: Long): String = timeFormat.format(Date(createdAt))

    fun formatQty(value: Double): String {
        return if (value % 1.0 == 0.0) value.toInt().toString() else moneyFormat.format(value)
    }

    fun formatMoney(value: Double): String = moneyFormat.format(value)

    fun resetTodayOrders() {
        if (!_state.value.canResetTodayOrders) {
            AppSnackbarController.show("No tienes permisos para reiniciar el contador.")
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(resettingOrders = true) }
            runCatching { inventoryRepository.resetTodayOrders() }
                .onSuccess {
                    _state.update {
                        it.copy(
                            resettingOrders = false,
                            confirmedOrdersToday = 0,
                            confirmedOrdersPreview = emptyList(),
                            showConfirmedOrdersPreview = false,
                            salesUsdText = "",
                            salesBsText = ""
                        )
                    }
                    AppSnackbarController.show("Contador de pedidos del día reiniciado.")
                }
                .onFailure { err ->
                    _state.update { it.copy(resettingOrders = false) }
                    AppSnackbarController.show(err.toUserMessage("No se pudo reiniciar el contador."))
                }
        }
    }

    fun refreshBcv() {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    bcvRefreshing = true,
                    dateText = dateFormat.format(Date())
                )
            }
            val result = bcvRateFetcher.fetchUsdRate()
            result.onSuccess { rate ->
                val rounded = roundRate(rate)
                inventoryRepository.saveBcvRate(rounded)
                onBcvRateAvailable(rounded, forceRateText = true)
                _state.update { it.copy(bcvRefreshing = false) }
            }.onFailure {
                _state.update { it.copy(bcvRefreshing = false) }
            }
        }
    }

    fun onBranchChange(value: String) {
        _state.update { it.copy(branchName = value) }
    }

    fun onDateChange(value: String) {
        _state.update { it.copy(dateText = value) }
    }

    fun onRateChange(raw: String) {
        val cleaned = cleanDecimal(raw)
        _state.update { it.copy(rateText = cleaned) }
    }

    fun onPrevCashUsdChange(raw: String) {
        _state.update { it.copy(prevCashUsdText = cleanDecimal(raw)) }
    }

    fun onPrevCashBsChange(raw: String) {
        _state.update { it.copy(prevCashBsText = cleanDecimal(raw)) }
    }

    fun prevCashUsdEquivBsText(): String =
        convertUsdToBs(_state.value.prevCashUsdText, currentRate())

    fun prevCashBsEquivUsdText(): String =
        convertBsToUsd(_state.value.prevCashBsText, currentRate())

    fun onSalesUsdChange(raw: String) {
        val cleaned = cleanDecimal(raw)
        val rate = currentRate()
        val bs = if (rate != null && cleaned.isNotBlank()) {
            val usd = cleaned.toDoubleOrNull()
            if (usd != null) formatDecimal(usd * rate) else ""
        } else ""
        _state.update { it.copy(salesUsdText = cleaned, salesBsText = bs) }
    }

    fun onSalesBsChange(raw: String) {
        val cleaned = cleanDecimal(raw)
        val rate = currentRate()
        val usd = if (rate != null && rate > 0 && cleaned.isNotBlank()) {
            val bs = cleaned.toDoubleOrNull()
            if (bs != null) formatDecimal(bs / rate) else ""
        } else ""
        _state.update { it.copy(salesBsText = cleaned, salesUsdText = usd) }
    }

    fun onPosNameChange(id: Long, name: String) {
        _state.update {
            it.copy(posEntries = it.posEntries.map { e ->
                if (e.id == id) e.copy(name = name) else e
            })
        }
    }

    fun onPosUsdChange(id: Long, raw: String) {
        val cleaned = cleanDecimal(raw)
        val rate = currentRate()
        _state.update {
            it.copy(posEntries = it.posEntries.map { e ->
                if (e.id != id) e
                else {
                    val bs = convertUsdToBs(cleaned, rate)
                    e.copy(usdText = cleaned, bsText = bs)
                }
            })
        }
    }

    fun onPosBsChange(id: Long, raw: String) {
        val cleaned = cleanDecimal(raw)
        val rate = currentRate()
        _state.update {
            it.copy(posEntries = it.posEntries.map { e ->
                if (e.id != id) e
                else {
                    val usd = convertBsToUsd(cleaned, rate)
                    e.copy(bsText = cleaned, usdText = usd)
                }
            })
        }
    }

    fun addPosEntry() {
        val n = _state.value.posEntries.size + 1
        _state.update { it.copy(posEntries = it.posEntries + newPosEntry("Punto $n")) }
    }

    fun removePosEntry(id: Long) {
        _state.update {
            val remaining = it.posEntries.filter { e -> e.id != id }
            it.copy(posEntries = if (remaining.isEmpty()) listOf(newPosEntry("Punto 1")) else remaining)
        }
    }

    fun onMobileRefChange(id: Long, ref: String) {
        _state.update {
            it.copy(mobileEntries = it.mobileEntries.map { e ->
                if (e.id == id) e.copy(ref = ref) else e
            })
        }
    }

    fun onMobileUsdChange(id: Long, raw: String) {
        val cleaned = cleanDecimal(raw)
        val rate = currentRate()
        _state.update {
            it.copy(mobileEntries = it.mobileEntries.map { e ->
                if (e.id != id) e
                else {
                    val bs = convertUsdToBs(cleaned, rate)
                    e.copy(usdText = cleaned, bsText = bs)
                }
            })
        }
    }

    fun onMobileBsChange(id: Long, raw: String) {
        val cleaned = cleanDecimal(raw)
        val rate = currentRate()
        _state.update {
            it.copy(mobileEntries = it.mobileEntries.map { e ->
                if (e.id != id) e
                else {
                    val usd = convertBsToUsd(cleaned, rate)
                    e.copy(bsText = cleaned, usdText = usd)
                }
            })
        }
    }

    fun addMobileEntry() {
        _state.update {
            it.copy(mobileEntries = it.mobileEntries + MobilePaymentEntry(id = nextId(), ref = ""))
        }
    }

    fun removeMobileEntry(id: Long) {
        _state.update { it.copy(mobileEntries = it.mobileEntries.filter { e -> e.id != id }) }
    }

    fun onCashDescChange(id: Long, desc: String) {
        _state.update {
            it.copy(cashEntries = it.cashEntries.map { e ->
                if (e.id == id) e.copy(description = desc) else e
            })
        }
    }

    fun onCashUsdChange(id: Long, raw: String) {
        val cleaned = cleanDecimal(raw)
        val rate = currentRate()
        _state.update {
            it.copy(cashEntries = it.cashEntries.map { e ->
                if (e.id != id) e
                else {
                    val bs = convertUsdToBs(cleaned, rate)
                    e.copy(usdText = cleaned, bsText = bs)
                }
            })
        }
    }

    fun onCashBsChange(id: Long, raw: String) {
        val cleaned = cleanDecimal(raw)
        val rate = currentRate()
        _state.update {
            it.copy(cashEntries = it.cashEntries.map { e ->
                if (e.id != id) e
                else {
                    val usd = convertBsToUsd(cleaned, rate)
                    e.copy(bsText = cleaned, usdText = usd)
                }
            })
        }
    }

    fun addCashEntry() {
        _state.update {
            it.copy(cashEntries = it.cashEntries + newCashEntry(""))
        }
    }

    fun removeCashEntry(id: Long) {
        _state.update {
            val remaining = it.cashEntries.filter { e -> e.id != id }
            it.copy(cashEntries = if (remaining.isEmpty()) listOf(newCashEntry("")) else remaining)
        }
    }

    fun onCasheaUsdChange(raw: String) {
        _state.update { it.copy(casheaUsdText = cleanDecimal(raw)) }
    }

    fun onExpenseDescChange(id: Long, desc: String) {
        _state.update {
            it.copy(expenseEntries = it.expenseEntries.map { e ->
                if (e.id == id) e.copy(description = desc) else e
            })
        }
    }

    fun onExpenseUsdChange(id: Long, raw: String) {
        val cleaned = cleanDecimal(raw)
        _state.update {
            it.copy(expenseEntries = it.expenseEntries.map { e ->
                if (e.id == id) e.copy(usdText = cleaned) else e
            })
        }
    }

    fun addExpenseEntry() {
        _state.update {
            it.copy(expenseEntries = it.expenseEntries + ExpenseEntry(id = nextId(), description = ""))
        }
    }

    fun removeExpenseEntry(id: Long) {
        _state.update { it.copy(expenseEntries = it.expenseEntries.filter { e -> e.id != id }) }
    }

    fun onObservationsChange(value: String) {
        _state.update { it.copy(observations = value) }
    }

    fun totalPosUsd(): Double = _state.value.posEntries.sumOf { parseUsd(it.usdText) }
    fun totalPosBs(): Double = _state.value.posEntries.sumOf { parseUsd(it.bsText) }

    fun totalMobileUsd(): Double = _state.value.mobileEntries.sumOf { parseUsd(it.usdText) }
    fun totalMobileBs(): Double = _state.value.mobileEntries.sumOf { parseUsd(it.bsText) }

    fun totalCashUsd(): Double = _state.value.cashEntries.sumOf { parseUsd(it.usdText) }
    fun totalCashBs(): Double = _state.value.cashEntries.sumOf { parseUsd(it.bsText) }

    fun totalExpenseUsd(): Double = _state.value.expenseEntries.sumOf { parseUsd(it.usdText) }

    fun casheaUsd(): Double = parseUsd(_state.value.casheaUsdText)

    fun prevCashUsd(): Double = parseUsd(_state.value.prevCashUsdText)
    fun prevCashBs(): Double = parseUsd(_state.value.prevCashBsText)

    fun salesUsd(): Double = parseUsd(_state.value.salesUsdText)
    fun salesBs(): Double = parseUsd(_state.value.salesBsText)

    fun grandTotalUsd(): Double =
        totalPosUsd() + totalMobileUsd() + totalCashUsd() + totalExpenseUsd() + casheaUsd()

    fun grandTotalBs(): Double {
        val rate = currentRate()
        return if (rate != null) grandTotalUsd() * rate else totalPosBs() + totalMobileBs() + totalCashBs()
    }

    fun differenceUsd(): Double = salesUsd() - grandTotalUsd()

    fun isBalanced(): Boolean = abs(differenceUsd()) < 0.01

    fun validateForClosing(): Boolean {
        val rate = currentRate()
        if (rate == null) {
            _state.update { it.copy(saveError = "Indica la tasa BCV antes de continuar.") }
            return false
        }
        _state.update { it.copy(saveError = null) }
        return true
    }

    fun saveClosingRecord(onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            _state.update { it.copy(saveError = null, saveSuccess = false) }
            val rate = currentRate()
            if (rate == null) {
                _state.update { it.copy(saveError = "Indica la tasa BCV antes de guardar.") }
                onComplete(false)
                return@launch
            }
            val sales = salesUsd()
            val grandUsd = grandTotalUsd()
            val diff = differenceUsd()
            val record = CashClosingRecord(
                branchName = _state.value.branchName,
                dateText = _state.value.dateText,
                closedAt = System.currentTimeMillis(),
                rate = rate,
                salesUsd = sales,
                salesBs = salesBs(),
                grandTotalUsd = grandUsd,
                grandTotalBs = grandTotalBs(),
                differenceUsd = diff,
                hasDifference = !isBalanced(),
                username = _state.value.username,
                observations = _state.value.observations,
                userSucursal = sessionManager.sucursal(),
                detailSnapshot = buildDetailSnapshot()
            )
            inventoryRepository.saveCashClosing(record)
                .onSuccess {
                    _state.update { it.copy(saveSuccess = true) }
                    onClosingSubmittedSound()
                    refreshClosingStatus()
                    onComplete(true)
                }
                .onFailure { err ->
                    val message = err.toUserMessage("No se pudo guardar el cierre.")
                    _state.update { it.copy(saveError = message) }
                    onComplete(false)
                    AppSnackbarController.show(message)
                }
        }
    }

    fun formatPrice(value: Double): String = "$${moneyFormat.format(value)}"

    fun formatBs(value: Double): String = "Bs ${moneyFormat.format(value)}"

    fun formatBsEquiv(usd: Double): String? {
        val rate = currentRate() ?: return null
        return formatBs(usd * rate)
    }

    fun textToAmount(text: String): Double = parseUsd(text)

    private fun buildDetailSnapshot(): String {
        val s = _state.value
        val snapshot = CashClosingSnapshot(
            branchName = s.branchName,
            userSucursal = sessionManager.sucursal(),
            prevCashUsd = prevCashUsd(),
            prevCashBs = prevCashBs(),
            salesUsd = salesUsd(),
            salesBs = salesBs(),
            posEntries = s.posEntries
                .filter { it.usdText.isNotBlank() || it.bsText.isNotBlank() }
                .map {
                    CashClosingSnapshot.SnapshotPosEntry(
                        name = it.name,
                        usd = parseUsd(it.usdText),
                        bs = parseUsd(it.bsText)
                    )
                },
            mobileEntries = s.mobileEntries
                .filter { it.usdText.isNotBlank() || it.bsText.isNotBlank() }
                .map {
                    CashClosingSnapshot.SnapshotMobileEntry(
                        ref = it.ref,
                        usd = parseUsd(it.usdText),
                        bs = parseUsd(it.bsText)
                    )
                },
            cashEntries = s.cashEntries
                .filter { it.description.isNotBlank() || it.usdText.isNotBlank() || it.bsText.isNotBlank() }
                .map {
                    CashClosingSnapshot.SnapshotCashEntry(
                        description = it.description,
                        usd = parseUsd(it.usdText),
                        bs = parseUsd(it.bsText)
                    )
                },
            cashUsd = totalCashUsd(),
            cashBs = totalCashBs(),
            casheaUsd = casheaUsd(),
            expenseEntries = s.expenseEntries
                .filter { it.usdText.isNotBlank() }
                .map {
                    CashClosingSnapshot.SnapshotExpenseEntry(
                        description = it.description,
                        usd = parseUsd(it.usdText)
                    )
                },
            observations = s.observations
        )
        return CashClosingSnapshotCodec.encode(snapshot)
    }

    fun buildWhatsAppMessage(): String {
        val s = _state.value
        val rate = currentRate()
        val totalA = totalPosUsd()
        val totalB = totalMobileUsd()
        val totalC = totalCashUsd()
        val totalD = totalExpenseUsd()
        val grand = grandTotalUsd()
        val grandBs = grandTotalBs()
        val sales = salesUsd()
        val salesBsVal = salesBs()
        val diff = differenceUsd()
        val balanced = isBalanced()

        return buildString {
            appendLine("================================")
            appendLine("*CIERRE DE CAJA*")
            appendLine("================================")
            appendLine()
            appendLine("Sucursal: *${s.branchName}*")
            appendLine("Fecha: ${s.dateText}")
            if (rate != null) {
                appendLine("Tasa BCV: Bs ${bcvRateFormat.format(rate)}")
            }
            val prevUsd = prevCashUsd()
            val prevBs = prevCashBs()
            if (prevUsd > 0) {
                appendLine(
                    "Efectivo cierre anterior (USD): ${formatPrice(prevUsd)} | ${formatBs(parseUsd(prevCashUsdEquivBsText()))}"
                )
            }
            if (prevBs > 0) {
                appendLine(
                    "Efectivo cierre anterior (Bs): ${formatBs(prevBs)} | ${formatPrice(parseUsd(prevCashBsEquivUsdText()))}"
                )
            }
            appendLine()
            appendLine("--- VENTAS DEL DIA ---")
            appendLine("Total: ${formatPrice(sales)}")
            appendLine("       ${formatBs(salesBsVal)}")
            appendLine()
            appendLine("--- DETALLE DEL CUADRE ---")
            appendLine()
            appendLine("*Puntos de venta (A)*")
            val posWithValues = s.posEntries.filter { it.usdText.isNotBlank() || it.bsText.isNotBlank() }
            if (posWithValues.isEmpty()) {
                appendLine("   Sin registros")
            } else {
                posWithValues.forEach { entry ->
                    val usd = parseUsd(entry.usdText)
                    val bs = parseUsd(entry.bsText)
                    appendLine("   - *${entry.name}*")
                    appendLine("     ${formatPrice(usd)} | ${formatBs(bs)}")
                }
            }
            appendLine("   Subtotal A: ${formatPrice(totalA)} | ${formatBs(totalPosBs())}")
            appendLine()
            appendLine("*Pago movil (B)*")
            val mobileWithValues = s.mobileEntries.filter { it.usdText.isNotBlank() || it.bsText.isNotBlank() }
            if (mobileWithValues.isEmpty()) {
                appendLine("   Sin registros")
            } else {
                mobileWithValues.forEach { entry ->
                    val usd = parseUsd(entry.usdText)
                    val bs = parseUsd(entry.bsText)
                    val refLabel = entry.ref.ifBlank { "—" }
                    appendLine("   - Ref. *$refLabel*")
                    appendLine("     ${formatPrice(usd)} | ${formatBs(bs)}")
                }
            }
            appendLine("   Subtotal B: ${formatPrice(totalB)} | ${formatBs(totalMobileBs())}")
            appendLine()
            appendLine("*Efectivo (C)*")
            val cashWithValues = s.cashEntries.filter {
                it.description.isNotBlank() || it.usdText.isNotBlank() || it.bsText.isNotBlank()
            }
            if (cashWithValues.isEmpty()) {
                appendLine("   Sin registros")
            } else {
                cashWithValues.forEach { entry ->
                    val usd = parseUsd(entry.usdText)
                    val bs = parseUsd(entry.bsText)
                    appendLine("   - *${entry.description.ifBlank { "Efectivo" }}*")
                    appendLine("     ${formatPrice(usd)} | ${formatBs(bs)}")
                }
            }
            appendLine("   Subtotal C: ${formatPrice(totalC)} | ${formatBs(totalCashBs())}")
            appendLine()
            appendLine("*Salidas (D)*")
            val expensesWithValues = s.expenseEntries.filter { it.usdText.isNotBlank() }
            if (expensesWithValues.isEmpty()) {
                appendLine("   Sin registros")
            } else {
                expensesWithValues.forEach { entry ->
                    appendLine("   - ${entry.description.ifBlank { "—" }}")
                    appendLine("     ${formatPrice(parseUsd(entry.usdText))}")
                }
            }
            appendLine("   Subtotal D: ${formatPrice(totalD)}")
            appendLine()
            val totalE = casheaUsd()
            appendLine("*Cashea (E)*")
            appendLine("   ${formatPrice(totalE)}")
            formatBsEquiv(totalE)?.let { appendLine("   $it") }
            appendLine()
            appendLine("================================")
            appendLine("*TOTAL (A+B+C+D+E)*")
            appendLine("   ${formatPrice(grand)}")
            appendLine("   ${formatBs(grandBs)}")
            appendLine("================================")
            appendLine()
            if (balanced) {
                appendLine("Estado: *Cuadre correcto*")
            } else {
                appendLine("Estado: *Diferencia de ${formatPrice(abs(diff))}*")
                appendLine(
                    if (diff > 0) "   Faltante en caja — requiere revision" else "   Sobrante en caja — requiere revision"
                )
            }
            appendLine()
            appendLine("Observaciones:")
            appendLine("   ${s.observations.ifBlank { "—" }}")
            appendLine()
            appendLine("Registrado por: ${s.username}")
        }
    }

    private fun onBcvRateAvailable(rate: Double?, forceRateText: Boolean) {
        _state.update { state ->
            val label = if (rate != null) {
                "Tasa BCV: Bs ${bcvRateFormat.format(rate)}"
            } else {
                "Tasa BCV: sin datos"
            }
            val shouldSetRateText = forceRateText || state.rateText.isBlank()
            val newRateText = if (shouldSetRateText && rate != null) {
                bcvRateFormat.format(rate)
            } else {
                state.rateText
            }
            val updated = state.copy(
                bcvRate = rate,
                bcvLabel = label,
                rateText = newRateText
            )
            val effectiveRate = rate ?: currentRateFromText(newRateText)
            if (shouldSetRateText && effectiveRate != null) {
                syncBsConversions(updated, effectiveRate)
            } else {
                updated
            }
        }
    }

    private fun syncBsConversions(state: CashClosingUiState, rate: Double): CashClosingUiState =
        state.copy(
            salesBsText = if (state.salesUsdText.isNotBlank()) {
                convertUsdToBs(state.salesUsdText, rate)
            } else {
                state.salesBsText
            },
            cashEntries = state.cashEntries.map { entry ->
                if (entry.usdText.isNotBlank()) {
                    entry.copy(bsText = convertUsdToBs(entry.usdText, rate))
                } else {
                    entry
                }
            },
            posEntries = state.posEntries.map { entry ->
                if (entry.usdText.isNotBlank()) {
                    entry.copy(bsText = convertUsdToBs(entry.usdText, rate))
                } else {
                    entry
                }
            },
            mobileEntries = state.mobileEntries.map { entry ->
                if (entry.usdText.isNotBlank()) {
                    entry.copy(bsText = convertUsdToBs(entry.usdText, rate))
                } else {
                    entry
                }
            }
        )

    private fun currentRateFromText(text: String): Double? =
        text.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }

    private fun newPosEntry(name: String) = PosEntry(id = nextId(), name = name)

    private fun newCashEntry(description: String) = CashEntry(id = nextId(), description = description)

    private fun nextId(): Long = nextId++

    private fun currentRate(): Double? =
        _state.value.rateText.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }

    private fun roundRate(rate: Double): Double = round(rate * 100) / 100.0

    private fun cleanDecimal(raw: String): String =
        raw.filter { it.isDigit() || it == '.' || it == ',' }
            .replace(',', '.')
            .let { text ->
                val parts = text.split('.')
                if (parts.size <= 1) text
                else parts.first() + "." + parts.drop(1).joinToString("").take(4)
            }

    private fun formatDecimal(value: Double): String {
        val rounded = round(value * 10000) / 10000.0
        if (rounded % 1.0 == 0.0) return rounded.toInt().toString()
        val formatted = inputDecimalFormat.format(rounded)
        return formatted.trimEnd('0').trimEnd('.')
    }

    private fun parseUsd(text: String): Double {
        if (text.isBlank()) return 0.0
        val trimmed = text.trim()
        val normalized = when {
            trimmed.contains(',') ->
                trimmed.replace(".", "").replace(',', '.')
            trimmed.count { it == '.' } > 1 ->
                trimmed.replace(".", "")
            else ->
                trimmed.replace(',', '.')
        }
        return normalized.toDoubleOrNull() ?: 0.0
    }

    private val inputDecimalFormat = NumberFormat.getNumberInstance(Locale.US).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 4
        isGroupingUsed = false
    }

    private fun convertUsdToBs(usdText: String, rate: Double?): String {
        if (rate == null || usdText.isBlank()) return ""
        val usd = usdText.toDoubleOrNull() ?: return ""
        return formatDecimal(usd * rate)
    }

    private fun convertBsToUsd(bsText: String, rate: Double?): String {
        if (rate == null || rate <= 0 || bsText.isBlank()) return ""
        val bs = bsText.toDoubleOrNull() ?: return ""
        return formatDecimal(bs / rate)
    }

    companion object {
        fun factory(
            inventoryRepository: InventoryRepository,
            sessionManager: SessionManager,
            bcvRateFetcher: BcvRateFetcher,
            onClosingSubmittedSound: () -> Unit = {}
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return CashClosingViewModel(
                    inventoryRepository,
                    sessionManager,
                    bcvRateFetcher,
                    onClosingSubmittedSound
                ) as T
            }
        }
    }
}
