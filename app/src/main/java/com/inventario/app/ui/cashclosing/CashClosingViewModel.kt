package com.inventario.app.ui.cashclosing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.inventario.app.data.bcv.BcvRateFetcher
import com.inventario.app.data.entity.CashClosingRecord
import com.inventario.app.data.repository.InventoryRepository
import com.inventario.app.data.session.SessionManager
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

data class CashClosingUiState(
    val branchName: String = "Total Care Automotriz",
    val dateText: String = "",
    val rateText: String = "",
    val prevCashUsdText: String = "",
    val prevCashBsText: String = "",
    val salesUsdText: String = "",
    val salesBsText: String = "",
    val posEntries: List<PosEntry> = emptyList(),
    val mobileEntries: List<MobilePaymentEntry> = emptyList(),
    val cashUsdText: String = "",
    val cashBsText: String = "",
    val casheaUsdText: String = "",
    val expenseEntries: List<ExpenseEntry> = emptyList(),
    val observations: String = "",
    val username: String = "",
    val loadingSales: Boolean = true,
    val bcvRate: Double? = null,
    val bcvLabel: String = "Tasa BCV: —",
    val bcvRefreshing: Boolean = false,
    val confirmedOrdersToday: Int = 0,
    val resettingOrders: Boolean = false
)

class CashClosingViewModel(
    private val inventoryRepository: InventoryRepository,
    private val sessionManager: SessionManager,
    private val bcvRateFetcher: BcvRateFetcher
) : ViewModel() {

    private val _state = MutableStateFlow(CashClosingUiState())
    val state: StateFlow<CashClosingUiState> = _state.asStateFlow()

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale("es", "VE"))
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
        _state.update {
            it.copy(
                username = sessionManager.username().orEmpty(),
                dateText = dateFormat.format(Date()),
                posEntries = listOf(newPosEntry("Punto 1"))
            )
        }
        viewModelScope.launch {
            inventoryRepository.observeMeta().collect { meta ->
                val rate = meta?.bcvRate?.let(::roundRate)
                onBcvRateAvailable(rate, forceRateText = false)
            }
        }
        viewModelScope.launch {
            val salesUsd = inventoryRepository.totalSalesToday()
            val orderCount = inventoryRepository.confirmedOrdersToday()
            _state.update {
                it.copy(
                    loadingSales = false,
                    confirmedOrdersToday = orderCount,
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
        refreshBcv()
    }

    fun resetTodayOrders() {
        viewModelScope.launch {
            _state.update { it.copy(resettingOrders = true) }
            runCatching { inventoryRepository.resetTodayOrders() }
                .onSuccess {
                    _state.update {
                        it.copy(
                            resettingOrders = false,
                            confirmedOrdersToday = 0,
                            salesUsdText = "",
                            salesBsText = ""
                        )
                    }
                }
                .onFailure {
                    _state.update { it.copy(resettingOrders = false) }
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
        val cleaned = cleanDecimal(raw)
        val rate = currentRate()
        val bs = convertUsdToBs(cleaned, rate)
        _state.update { it.copy(prevCashUsdText = cleaned, prevCashBsText = bs) }
    }

    fun onPrevCashBsChange(raw: String) {
        val cleaned = cleanDecimal(raw)
        val rate = currentRate()
        val usd = convertBsToUsd(cleaned, rate)
        _state.update { it.copy(prevCashBsText = cleaned, prevCashUsdText = usd) }
    }

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

    fun onCashUsdChange(raw: String) {
        val cleaned = cleanDecimal(raw)
        val rate = currentRate()
        val bs = convertUsdToBs(cleaned, rate)
        _state.update { it.copy(cashUsdText = cleaned, cashBsText = bs) }
    }

    fun onCashBsChange(raw: String) {
        val cleaned = cleanDecimal(raw)
        val rate = currentRate()
        val usd = convertBsToUsd(cleaned, rate)
        _state.update { it.copy(cashBsText = cleaned, cashUsdText = usd) }
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

    fun totalCashUsd(): Double = parseUsd(_state.value.cashUsdText)
    fun totalCashBs(): Double = parseUsd(_state.value.cashBsText)

    fun totalExpenseUsd(): Double = _state.value.expenseEntries.sumOf { parseUsd(it.usdText) }

    fun casheaUsd(): Double = parseUsd(_state.value.casheaUsdText)

    fun prevCashUsd(): Double = parseUsd(_state.value.prevCashUsdText)
    fun prevCashBs(): Double = parseUsd(_state.value.prevCashBsText)

    fun salesUsd(): Double = parseUsd(_state.value.salesUsdText)
    fun salesBs(): Double = parseUsd(_state.value.salesBsText)

    fun grandTotalUsd(): Double = totalPosUsd() + totalMobileUsd() + totalCashUsd() + totalExpenseUsd()

    fun grandTotalBs(): Double {
        val rate = currentRate()
        return if (rate != null) grandTotalUsd() * rate else totalPosBs() + totalMobileBs() + totalCashBs()
    }

    fun differenceUsd(): Double = salesUsd() - grandTotalUsd()

    fun isBalanced(): Boolean = abs(differenceUsd()) < 0.01

    fun saveClosingRecord() {
        viewModelScope.launch {
            val rate = currentRate() ?: return@launch
            val sales = salesUsd()
            val grandUsd = grandTotalUsd()
            val diff = differenceUsd()
            inventoryRepository.saveCashClosing(
                CashClosingRecord(
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
                    observations = _state.value.observations
                )
            )
        }
    }

    fun formatPrice(value: Double): String = "$${moneyFormat.format(value)}"

    fun formatBs(value: Double): String = "Bs ${moneyFormat.format(value)}"

    fun formatBsEquiv(usd: Double): String? {
        val rate = currentRate() ?: return null
        return formatBs(usd * rate)
    }

    fun textToAmount(text: String): Double = parseUsd(text)

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
            appendLine("━━━━━━━━━━━━━━━━━━━━")
            appendLine("🧾 *CUADRE DE CAJA*")
            appendLine("━━━━━━━━━━━━━━━━━━━━")
            appendLine()
            appendLine("📍 *${s.branchName}*")
            appendLine("📅 ${s.dateText}")
            if (rate != null) {
                appendLine("💱 Tasa BCV: Bs ${bcvRateFormat.format(rate)}")
            }
            val prevCash = prevCashUsd()
            if (prevCash > 0) {
                appendLine("💵 Efectivo cierre anterior: ${formatPrice(prevCash)} · ${formatBs(prevCashBs())}")
            }
            appendLine()
            appendLine("──── *Ventas del día* ────")
            appendLine("💰 ${formatPrice(sales)}")
            appendLine("   ${formatBs(salesBsVal)}")
            appendLine()
            appendLine("──── *Detalle del cuadre* ────")
            appendLine()
            appendLine("🏪 *Puntos de venta (A)*")
            val posWithValues = s.posEntries.filter { it.usdText.isNotBlank() || it.bsText.isNotBlank() }
            if (posWithValues.isEmpty()) {
                appendLine("   — sin registros —")
            } else {
                posWithValues.forEach { entry ->
                    val usd = parseUsd(entry.usdText)
                    val bs = parseUsd(entry.bsText)
                    appendLine("   • *${entry.name}*")
                    appendLine("     ${formatPrice(usd)} · ${formatBs(bs)}")
                }
            }
            appendLine("   _Subtotal A:_ ${formatPrice(totalA)} · ${formatBs(totalPosBs())}")
            appendLine()
            appendLine("📱 *Pago móvil (B)*")
            val mobileWithValues = s.mobileEntries.filter { it.usdText.isNotBlank() || it.bsText.isNotBlank() }
            if (mobileWithValues.isEmpty()) {
                appendLine("   — sin registros —")
            } else {
                mobileWithValues.forEach { entry ->
                    val usd = parseUsd(entry.usdText)
                    val bs = parseUsd(entry.bsText)
                    val refLabel = entry.ref.ifBlank { "—" }
                    appendLine("   • Ref *$refLabel*")
                    appendLine("     ${formatPrice(usd)} · ${formatBs(bs)}")
                }
            }
            appendLine("   _Subtotal B:_ ${formatPrice(totalB)} · ${formatBs(totalMobileBs())}")
            appendLine()
            appendLine("💵 *Efectivo (C)*")
            appendLine("   ${formatPrice(totalC)} · ${formatBs(totalCashBs())}")
            appendLine()
            val cashea = casheaUsd()
            if (cashea > 0) {
                appendLine("🏦 *Cashea (informativo)*")
                appendLine("   ${formatPrice(cashea)}")
                formatBsEquiv(cashea)?.let { appendLine("   $it") }
                appendLine()
            }
            appendLine("📤 *Salidas (D)*")
            val expensesWithValues = s.expenseEntries.filter { it.usdText.isNotBlank() }
            if (expensesWithValues.isEmpty()) {
                appendLine("   — sin registros —")
            } else {
                expensesWithValues.forEach { entry ->
                    appendLine("   • ${entry.description.ifBlank { "—" }}")
                    appendLine("     ${formatPrice(parseUsd(entry.usdText))}")
                }
            }
            appendLine("   _Subtotal D:_ ${formatPrice(totalD)}")
            appendLine()
            appendLine("━━━━━━━━━━━━━━━━━━━━")
            appendLine("📊 *TOTAL (A+B+C+D)*")
            appendLine("   ${formatPrice(grand)}")
            appendLine("   ${formatBs(grandBs)}")
            appendLine("━━━━━━━━━━━━━━━━━━━━")
            appendLine()
            if (balanced) {
                appendLine("✅ *Cuadra con ventas*")
            } else {
                appendLine("⚠️ *DIFERENCIA: ${formatPrice(abs(diff))}*")
                appendLine(
                    if (diff > 0) "   Faltan en caja — revisar" else "   Sobra en caja — revisar"
                )
            }
            appendLine()
            appendLine("📝 *Observaciones*")
            appendLine("   ${s.observations.ifBlank { "—" }}")
            appendLine()
            appendLine("👤 Registrado por: ${s.username}")
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
            prevCashBsText = if (state.prevCashUsdText.isNotBlank()) {
                convertUsdToBs(state.prevCashUsdText, rate)
            } else {
                state.prevCashBsText
            },
            salesBsText = if (state.salesUsdText.isNotBlank()) {
                convertUsdToBs(state.salesUsdText, rate)
            } else {
                state.salesBsText
            },
            cashBsText = if (state.cashUsdText.isNotBlank()) {
                convertUsdToBs(state.cashUsdText, rate)
            } else {
                state.cashBsText
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
            bcvRateFetcher: BcvRateFetcher
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return CashClosingViewModel(inventoryRepository, sessionManager, bcvRateFetcher) as T
            }
        }
    }
}
