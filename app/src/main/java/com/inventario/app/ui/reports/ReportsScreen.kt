package com.inventario.app.ui.reports

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.inventario.app.data.entity.CashClosingRecord
import com.inventario.app.data.entity.CashClosingSnapshot
import com.inventario.app.data.entity.CashClosingSnapshotCodec
import com.inventario.app.data.entity.CashClosingStatus
import com.inventario.app.data.entity.PENDING_SUCURSAL_LABEL
import com.inventario.app.data.entity.UserRole
import com.inventario.app.data.entity.canExportClosingHistory
import com.inventario.app.data.entity.canReviewClosings
import com.inventario.app.data.entity.canViewClosingHistory
import com.inventario.app.data.entity.shouldReceiveClosingExcelReminder
import com.inventario.app.data.excel.CashClosingExcelExporter
import com.inventario.app.data.excel.CashClosingHistoryExport
import com.inventario.app.data.entity.displaySucursalOrPending
import com.inventario.app.data.entity.displaySalesDiscountUsd
import com.inventario.app.data.entity.displaySalesGrossUsd
import com.inventario.app.data.repository.ReportsRepository
import com.inventario.app.data.repository.ReportsSummary
import com.inventario.app.data.session.SessionManager
import com.inventario.app.data.sync.CloudEvent
import com.inventario.app.data.sync.toUserMessage
import com.inventario.app.ui.cashclosing.ClosingHistoryPresentation
import com.inventario.app.ui.cashclosing.ClosingHistorySection
import com.inventario.app.ui.theme.AccentSectionCard
import com.inventario.app.ui.theme.AppScreenBackground
import com.inventario.app.ui.theme.BrandAppTopBar
import com.inventario.app.ui.theme.BrandOutflow
import com.inventario.app.ui.theme.BrandSuccess
import com.inventario.app.ui.theme.BrandWarning
import com.inventario.app.ui.theme.ConfirmCheckDialog
import com.inventario.app.ui.theme.ReportDivider
import com.inventario.app.ui.theme.ReportHeader
import com.inventario.app.ui.theme.ReportKeyValueRow
import com.inventario.app.ui.theme.ReportTotalBanner
import com.inventario.app.ui.theme.AppSnackbarController
import com.inventario.app.util.AppNotificationMessages
import com.inventario.app.ui.theme.StatusPill
import com.inventario.app.ui.theme.screenHorizontalPadding
import com.inventario.app.ui.theme.screenVerticalPadding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.math.abs

enum class ClosingReviewAction {
    APPROVE,
    REJECT,
    REVERT
}

private enum class ReportsTab(val label: String) {
    APPROVAL("Flujo aprobación"),
    HISTORY("Historial")
}

/** Convierte los milisegundos UTC-medianoche que entrega el DatePicker de Material3
 *  al texto "dd/MM/yyyy" en la zona horaria local del dispositivo. */
private fun utcMidnightMillisToLocalDateText(utcMidnightMillis: Long, dateFormat: SimpleDateFormat): String {
    val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMidnightMillis }
    val localCal = Calendar.getInstance().apply {
        clear()
        set(utcCal.get(Calendar.YEAR), utcCal.get(Calendar.MONTH), utcCal.get(Calendar.DAY_OF_MONTH))
    }
    return dateFormat.format(localCal.time)
}

/** Inverso de [utcMidnightMillisToLocalDateText]: usado para posicionar el DatePicker
 *  en la fecha ya seleccionada. */
private fun localDateTextToUtcMidnightMillis(text: String, dateFormat: SimpleDateFormat): Long? {
    val date = runCatching { dateFormat.parse(text.trim()) }.getOrNull() ?: return null
    val localCal = Calendar.getInstance().apply { time = date }
    val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(localCal.get(Calendar.YEAR), localCal.get(Calendar.MONTH), localCal.get(Calendar.DAY_OF_MONTH))
    }
    return utcCal.timeInMillis
}

/** Rango seleccionable en el calendario: hoy y hasta MAX_RANGE_DAYS hacia atrás (en UTC-medianoche). */
private fun selectableUtcRangeBounds(): Pair<Long, Long> {
    val now = Calendar.getInstance()
    val utcToday = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
    val minMillis = utcToday - TimeUnit.DAYS.toMillis(ReportsRepository.MAX_RANGE_DAYS.toLong())
    return minMillis to utcToday
}

data class ReportsUiState(
    val startDateText: String = "",
    val endDateText: String = "",
    val historyStartDateText: String = "",
    val historyEndDateText: String = "",
    val loading: Boolean = true,
    val error: String? = null,
    val actionMessage: String? = null,
    val actionError: String? = null,
    val summary: ReportsSummary? = null,
    val bcvLabel: String = "Tasa BCV: —",
    val canReviewClosings: Boolean = false,
    val canViewClosingHistory: Boolean = false,
    val canExportClosingHistory: Boolean = false,
    val pendingClosingCount: Int = 0,
    val todayDateText: String = "",
    val closingHistory: List<CashClosingRecord> = emptyList(),
    val loadingClosingHistory: Boolean = false,
    val exportingClosingHistory: Boolean = false
)

class ReportsViewModel(
    private val reportsRepository: ReportsRepository,
    private val bcvRateProvider: suspend () -> Double?,
    private val sessionManager: SessionManager,
    private val userRole: UserRole,
    private val cloudEvents: SharedFlow<CloudEvent>? = null,
    private val onReviewCompleted: (dedupeKey: String, title: String, message: String) -> Unit = { _, _, _ -> }
) : ViewModel() {
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale("es", "VE"))
    private val timeFormat = SimpleDateFormat("HH:mm", Locale("es", "VE"))
    private val moneyFormat = NumberFormat.getNumberInstance(Locale("es", "VE")).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }

    private val _state = MutableStateFlow(
        ReportsUiState(
            canReviewClosings = userRole.canReviewClosings(),
            canViewClosingHistory = userRole.canViewClosingHistory(),
            canExportClosingHistory = userRole.canExportClosingHistory(),
            todayDateText = SimpleDateFormat("dd/MM/yyyy", Locale("es", "VE")).format(Date())
        )
    )
    val state: StateFlow<ReportsUiState> = _state.asStateFlow()

    init {
        // Por defecto el flujo de aprobación muestra el día actual. Todos los
        // segmentos (pendientes, diferencias, aprobados, rechazados) respetan
        // el rango de fechas seleccionado.
        val today = Calendar.getInstance()
        val historyStart = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, -(ReportsRepository.MAX_RANGE_DAYS - 1))
        }
        val todayText = dateFormat.format(today.time)
        _state.update {
            it.copy(
                startDateText = todayText,
                endDateText = todayText,
                historyStartDateText = dateFormat.format(historyStart.time),
                historyEndDateText = todayText
            )
        }
        load()
        refreshClosingHistory()
        cloudEvents?.let { events ->
            viewModelScope.launch {
                // Otro Admin/Supervisor aprobando, rechazando o revirtiendo un
                // cierre desde su propio dispositivo debe reflejarse aquí sin
                // que este usuario tenga que salir y volver a entrar.
                events.collect { event ->
                    if (event is CloudEvent.CashClosings) {
                        load()
                        refreshClosingHistory()
                    }
                }
            }
        }
    }

    fun onStartDateChange(value: String) {
        _state.update { it.copy(startDateText = value, error = null) }
    }

    fun onEndDateChange(value: String) {
        _state.update { it.copy(endDateText = value, error = null) }
    }

    /** [utcMidnightMillis] proviene del DatePicker de Material3, que trabaja siempre en UTC. */
    fun onDatePicked(isStart: Boolean, utcMidnightMillis: Long) {
        val text = utcMidnightMillisToLocalDateText(utcMidnightMillis, dateFormat)
        if (isStart) onStartDateChange(text) else onEndDateChange(text)
        load()
    }

    /** Atajo de calendario en Flujo aprobación: "Hoy", "7 días", etc. */
    fun selectQuickRange(daysBack: Int) {
        val end = Calendar.getInstance()
        val start = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -daysBack) }
        _state.update {
            it.copy(
                startDateText = dateFormat.format(start.time),
                endDateText = dateFormat.format(end.time),
                error = null
            )
        }
        load()
    }

    fun onHistoryStartDateChange(value: String) {
        _state.update { it.copy(historyStartDateText = value) }
    }

    fun onHistoryEndDateChange(value: String) {
        _state.update { it.copy(historyEndDateText = value) }
    }

    fun onHistoryDatePicked(isStart: Boolean, utcMidnightMillis: Long) {
        val text = utcMidnightMillisToLocalDateText(utcMidnightMillis, dateFormat)
        if (isStart) onHistoryStartDateChange(text) else onHistoryEndDateChange(text)
    }

    /** Atajo de calendario en Historial. */
    fun selectHistoryQuickRange(daysBack: Int) {
        val end = Calendar.getInstance()
        val start = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -daysBack) }
        _state.update {
            it.copy(
                historyStartDateText = dateFormat.format(start.time),
                historyEndDateText = dateFormat.format(end.time)
            )
        }
    }

    fun filteredClosingHistory(): List<CashClosingRecord> {
        val start = parseDate(_state.value.historyStartDateText) ?: return emptyList()
        val endExclusive = parseDate(_state.value.historyEndDateText)?.let { dayEndExclusive(it) }
            ?: return emptyList()
        if (start >= endExclusive) return emptyList()
        return _state.value.closingHistory.filter { closing ->
            closing.closedAt >= start && closing.closedAt < endExclusive
        }
    }

    fun historyPeriodLabel(): String =
        "${_state.value.historyStartDateText} – ${_state.value.historyEndDateText}"

    fun clearActionFeedback() {
        _state.update { it.copy(actionMessage = null, actionError = null) }
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val start = parseDate(_state.value.startDateText)
            val endExclusive = parseDate(_state.value.endDateText)?.let { dayEndExclusive(it) }
            if (start == null || endExclusive == null) {
                _state.update {
                    it.copy(loading = false, error = "Fechas inválidas. Usa formato dd/MM/yyyy.")
                }
                return@launch
            }
            if (start >= endExclusive) {
                _state.update {
                    it.copy(loading = false, error = "La fecha inicial debe ser anterior a la final.")
                }
                return@launch
            }
            val spanDays = ReportsRepository.rangeSpanDays(start, endExclusive)
            if (spanDays > ReportsRepository.MAX_RANGE_DAYS) {
                _state.update {
                    it.copy(
                        loading = false,
                        error = "El rango máximo permitido es ${ReportsRepository.MAX_RANGE_DAYS} días."
                    )
                }
                return@launch
            }
            val (clampedStart, clampedEnd) = ReportsRepository.clampRange(start, endExclusive)
            val rate = bcvRateProvider()
            runCatching {
                reportsRepository.loadSummary(clampedStart, clampedEnd, rate)
            }.onSuccess { summary ->
                val pendingCount = summary.balancedPendingClosings.size +
                    summary.differencePendingClosings.size
                _state.update {
                    it.copy(
                        loading = false,
                        summary = summary,
                        pendingClosingCount = pendingCount,
                        bcvLabel = rate?.let { r -> "Tasa BCV: Bs ${moneyFormat.format(r)}" } ?: "Tasa BCV: —"
                    )
                }
            }.onFailure { err ->
                _state.update {
                    it.copy(loading = false, error = err.toUserMessage("No se pudieron cargar los reportes."))
                }
            }
        }
    }

    fun reviewClosing(id: Long, action: ClosingReviewAction) {
        if (!userRole.canReviewClosings()) {
            _state.update { it.copy(actionError = "No tienes permisos para validar cierres.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(actionMessage = null, actionError = null) }
            val reviewer = sessionManager.username().orEmpty()
            val result = when (action) {
                ClosingReviewAction.APPROVE ->
                    reportsRepository.approveClosing(id, reviewer)
                ClosingReviewAction.REJECT ->
                    reportsRepository.rejectClosing(id, reviewer)
                ClosingReviewAction.REVERT ->
                    reportsRepository.revertClosing(id, reviewer)
            }
            result.onSuccess {
                val message = when (action) {
                    ClosingReviewAction.APPROVE -> "Cierre aprobado y contabilizado."
                    ClosingReviewAction.REJECT -> "Cierre rechazado. El usuario debe volver a ejecutarlo."
                    ClosingReviewAction.REVERT ->
                        "Cierre revertido. Ya no cuenta en Recaudación Total y el usuario puede registrar uno nuevo."
                }
                val (title, popupMessage) = when (action) {
                    ClosingReviewAction.APPROVE -> AppNotificationMessages.cashClosingApprovedByReviewer()
                    ClosingReviewAction.REJECT -> AppNotificationMessages.cashClosingRejectedByReviewer()
                    ClosingReviewAction.REVERT -> AppNotificationMessages.cashClosingRevertedByReviewer()
                }
                _state.update { it.copy(actionMessage = message) }
                load()
                refreshClosingHistory()
                onReviewCompleted("review_${action.name}_$id", title, popupMessage)
            }.onFailure { err ->
                val message = err.toUserMessage("No se pudo completar la acción.")
                _state.update {
                    it.copy(actionError = message)
                }
                AppSnackbarController.show(message)
            }
        }
    }

    fun formatUsd(value: Double): String = "$${moneyFormat.format(value)}"
    fun formatBs(value: Double): String = "Bs ${moneyFormat.format(value)}"
    fun formatRate(value: Double): String = moneyFormat.format(value)

    fun formatClosingDateTime(closedAt: Long): String =
        "${dateFormat.format(Date(closedAt))} ${timeFormat.format(Date(closedAt))}"

    fun formatClosingPrice(value: Double): String = "$${moneyFormat.format(value)}"

    fun suggestedClosingExportFileName(): String = CashClosingExcelExporter.suggestedFileName()

    fun refreshClosingHistory() {
        if (!_state.value.canViewClosingHistory) return
        viewModelScope.launch {
            _state.update { it.copy(loadingClosingHistory = true) }
            runCatching { reportsRepository.listClosingHistory() }
                .onSuccess { list ->
                    _state.update {
                        it.copy(
                            closingHistory = list,
                            loadingClosingHistory = false,
                            todayDateText = dateFormat.format(Date())
                        )
                    }
                }
                .onFailure {
                    _state.update { it.copy(loadingClosingHistory = false) }
                }
        }
    }

    suspend fun exportClosingHistoryToUri(resolver: ContentResolver, uri: Uri): Result<Unit> =
        CashClosingHistoryExport.writeToUri(resolver, uri, filteredClosingHistory())

    fun requestClosingHistoryExport() {
        if (!_state.value.canExportClosingHistory) return
        if (filteredClosingHistory().isEmpty()) {
            AppSnackbarController.show("No hay cierres para exportar en el período seleccionado.")
            return
        }
        _state.update { it.copy(exportingClosingHistory = true) }
    }

    fun finishClosingHistoryExport(success: Boolean, errorMessage: String? = null) {
        if (success) {
            val username = sessionManager.username().orEmpty()
            if (shouldReceiveClosingExcelReminder(userRole, username)) {
                sessionManager.markClosingExcelExportedToday(username)
            }
        }
        _state.update { it.copy(exportingClosingHistory = false) }
        val message = when {
            success -> "Reporte Excel exportado correctamente."
            errorMessage != null -> errorMessage
            else -> "No se pudo exportar el reporte."
        }
        AppSnackbarController.show(message)
    }

    private fun parseDate(text: String): Long? = runCatching {
        dateFormat.parse(text.trim())?.time
    }.getOrNull()

    private fun dayEndExclusive(dayStart: Long): Long =
        dayStart + TimeUnit.DAYS.toMillis(1)

    companion object {
        fun factory(
            reportsRepository: ReportsRepository,
            bcvRateProvider: suspend () -> Double?,
            sessionManager: SessionManager,
            userRole: UserRole,
            cloudEvents: SharedFlow<CloudEvent>? = null,
            onReviewCompleted: (dedupeKey: String, title: String, message: String) -> Unit = { _, _, _ -> }
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ReportsViewModel(
                    reportsRepository,
                    bcvRateProvider,
                    sessionManager,
                    userRole,
                    cloudEvents,
                    onReviewCompleted
                ) as T
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(
    viewModel: ReportsViewModel,
    subtitle: String,
    onBack: () -> Unit,
    onLogout: () -> Unit,
    onRefreshBcv: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val approvalScrollState = rememberScrollState()
    val historyScrollState = rememberScrollState()
    var selectedTabIndex by remember { mutableIntStateOf(ReportsTab.APPROVAL.ordinal) }
    val showHistoryTab = state.canViewClosingHistory

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        )
    ) { uri ->
        if (uri == null) {
            viewModel.finishClosingHistoryExport(success = false, errorMessage = "Exportación cancelada.")
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val result = viewModel.exportClosingHistoryToUri(context.contentResolver, uri)
            viewModel.finishClosingHistoryExport(
                success = result.isSuccess,
                errorMessage = result.exceptionOrNull()?.message
            )
        }
    }

    Scaffold(
        topBar = {
            BrandAppTopBar(
                subtitle = subtitle,
                onRefreshBcv = onRefreshBcv,
                onLogout = onLogout,
                showBack = true,
                onBack = onBack
            )
        }
    ) { padding ->
        AppScreenBackground(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = screenHorizontalPadding(), vertical = screenVerticalPadding())
                ) {
                    Text(
                        text = "Flujo Aprobación",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = if (showHistoryTab && selectedTabIndex == ReportsTab.HISTORY.ordinal) {
                            "Filtra el historial por rango de fechas y exporta a Excel."
                        } else {
                            "Por defecto muestra cierres del día actual. " +
                                "Usa las fechas o atajos para consultar otro período (hasta " +
                                "${ReportsRepository.MAX_RANGE_DAYS} días)."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (showHistoryTab) {
                    PrimaryTabRow(
                        selectedTabIndex = selectedTabIndex,
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.primary
                    ) {
                        ReportsTab.entries.forEachIndexed { index, tab ->
                            Tab(
                                selected = selectedTabIndex == index,
                                onClick = { selectedTabIndex = index },
                                text = {
                                    Text(
                                        text = tab.label,
                                        fontWeight = if (selectedTabIndex == index) {
                                            FontWeight.SemiBold
                                        } else {
                                            FontWeight.Medium
                                        }
                                    )
                                }
                            )
                        }
                    }
                }

                when {
                    showHistoryTab && selectedTabIndex == ReportsTab.HISTORY.ordinal -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .imePadding()
                                .verticalScroll(historyScrollState)
                                .padding(
                                    horizontal = screenHorizontalPadding(),
                                    vertical = screenVerticalPadding()
                                )
                        ) {
                            ReportsHistoryContent(state = state, viewModel = viewModel) {
                                viewModel.requestClosingHistoryExport()
                                exportLauncher.launch(viewModel.suggestedClosingExportFileName())
                            }
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                    else -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .imePadding()
                                .verticalScroll(approvalScrollState)
                                .padding(
                                    horizontal = screenHorizontalPadding(),
                                    vertical = screenVerticalPadding()
                                )
                        ) {
                            ReportsApprovalContent(state = state, viewModel = viewModel)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportsHistoryContent(
    state: ReportsUiState,
    viewModel: ReportsViewModel,
    onExportExcel: () -> Unit
) {
    val filteredClosings = viewModel.filteredClosingHistory()
    DateRangeSelector(
        startDateText = state.historyStartDateText,
        endDateText = state.historyEndDateText,
        onStartPicked = { viewModel.onHistoryDatePicked(isStart = true, utcMidnightMillis = it) },
        onEndPicked = { viewModel.onHistoryDatePicked(isStart = false, utcMidnightMillis = it) }
    )
    Spacer(Modifier.height(8.dp))
    QuickRangeChips(
        onSelectRange = { days ->
            viewModel.selectHistoryQuickRange(days)
        }
    )
    Spacer(Modifier.height(12.dp))
    ClosingHistorySection(
        presentation = ClosingHistoryPresentation(
            todayDateText = state.todayDateText,
            closings = filteredClosings,
            loading = state.loadingClosingHistory,
            canExport = state.canExportClosingHistory,
            exporting = state.exportingClosingHistory,
            periodLabel = viewModel.historyPeriodLabel(),
            groupByToday = false
        ),
        formatDateTime = viewModel::formatClosingDateTime,
        formatPrice = viewModel::formatClosingPrice,
        onRefresh = viewModel::refreshClosingHistory,
        onExportExcel = onExportExcel
    )
}

@Composable
private fun ReportsApprovalContent(
    state: ReportsUiState,
    viewModel: ReportsViewModel
) {
    DateRangeSelector(
        startDateText = state.startDateText,
        endDateText = state.endDateText,
        onStartPicked = { viewModel.onDatePicked(isStart = true, utcMidnightMillis = it) },
        onEndPicked = { viewModel.onDatePicked(isStart = false, utcMidnightMillis = it) }
    )
    Spacer(Modifier.height(8.dp))
    QuickRangeChips(
        onSelectRange = { days ->
            viewModel.selectQuickRange(days)
        }
    )
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = viewModel::load,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text("Consultar")
    }
    if (state.error != null) {
        Spacer(Modifier.height(8.dp))
        Text(state.error!!, color = MaterialTheme.colorScheme.error)
    }
    if (state.actionMessage != null) {
        Spacer(Modifier.height(8.dp))
        Text(state.actionMessage!!, color = BrandSuccess)
    }
    if (state.actionError != null) {
        Spacer(Modifier.height(8.dp))
        Text(state.actionError!!, color = MaterialTheme.colorScheme.error)
    }
    if (state.loading) {
        Spacer(Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator()
        }
    } else {
        state.summary?.let { summary ->
            Spacer(Modifier.height(16.dp))
            ReportsContent(
                summary = summary,
                viewModel = viewModel,
                canReview = state.canReviewClosings,
                pendingCount = state.pendingClosingCount,
                periodLabel = "${state.startDateText} – ${state.endDateText}"
            )
        }
    }
}

/** Selector de rango de fechas mediante calendario nativo (Material3 DatePicker). */
@Composable
private fun DateRangeSelector(
    startDateText: String,
    endDateText: String,
    onStartPicked: (Long) -> Unit,
    onEndPicked: (Long) -> Unit
) {
    val (minMillis, maxMillis) = remember { selectableUtcRangeBounds() }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        DatePickerField(
            label = "Desde",
            dateText = startDateText,
            minMillis = minMillis,
            maxMillis = maxMillis,
            onDatePicked = onStartPicked,
            modifier = Modifier.weight(1f)
        )
        DatePickerField(
            label = "Hasta",
            dateText = endDateText,
            minMillis = minMillis,
            maxMillis = maxMillis,
            onDatePicked = onEndPicked,
            modifier = Modifier.weight(1f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerField(
    label: String,
    dateText: String,
    minMillis: Long,
    maxMillis: Long,
    onDatePicked: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var showPicker by remember { mutableStateOf(false) }
    val fieldDateFormat = remember { SimpleDateFormat("dd/MM/yyyy", Locale("es", "VE")) }

    OutlinedButton(
        onClick = { showPicker = true },
        modifier = modifier,
        shape = RoundedCornerShape(12.dp)
    ) {
        Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(dateText.ifBlank { "Seleccionar" }, style = MaterialTheme.typography.bodyMedium)
        }
    }

    if (showPicker) {
        val initialMillis = localDateTextToUtcMidnightMillis(dateText, fieldDateFormat)
            ?.coerceIn(minMillis, maxMillis) ?: maxMillis
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = initialMillis,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    utcTimeMillis in minMillis..maxMillis
            }
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let(onDatePicked)
                    showPicker = false
                }) {
                    Text("Aceptar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) {
                    Text("Cancelar")
                }
            }
        ) {
            DatePicker(state = pickerState, showModeToggle = false)
        }
    }
}

@Composable
private fun QuickRangeChips(onSelectRange: (Int) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState())
    ) {
        FilterChip(selected = false, onClick = { onSelectRange(0) }, label = { Text("Hoy") })
        FilterChip(selected = false, onClick = { onSelectRange(7) }, label = { Text("7 días") })
        FilterChip(selected = false, onClick = { onSelectRange(30) }, label = { Text("30 días") })
        FilterChip(selected = false, onClick = { onSelectRange(ReportsRepository.MAX_RANGE_DAYS) }, label = { Text("90 días") })
    }
}

@Composable
private fun ReportsContent(
    summary: ReportsSummary,
    viewModel: ReportsViewModel,
    canReview: Boolean,
    pendingCount: Int,
    periodLabel: String
) {
    var showClosingsBreakdown by remember { mutableStateOf(false) }

    if (showClosingsBreakdown) {
        ApprovedClosingsBreakdownDialog(
            closings = summary.approvedClosings,
            viewModel = viewModel,
            onDismiss = { showClosingsBreakdown = false }
        )
    }

    KpiCard(
        title = "Recaudación Total",
        lines = if (summary.approvedClosings.isEmpty()) {
            listOf("Sin cierres aprobados en el período")
        } else {
            listOf(
                "Período: $periodLabel",
                viewModel.formatUsd(summary.approvedClosingIncomeUsd),
                viewModel.formatBs(summary.approvedClosingIncomeBs),
                "Tasa del día: ${formatApprovedRatesLabel(summary.approvedClosings, viewModel)}",
                "${summary.approvedClosings.size} cierres contabilizados"
            )
        },
        footer = if (summary.approvedClosings.isNotEmpty()) {
            {
                OutlinedButton(
                    onClick = { showClosingsBreakdown = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Ver detalle por cierre")
                }
            }
        } else {
            null
        }
    )
    Spacer(Modifier.height(12.dp))
    AccentSectionCard(
        title = "Cierre de caja pendiente por validar",
        titleTrailing = {
            if (pendingCount > 0) {
                PendingBellBadge(count = summary.balancedPendingClosings.size)
            }
        }
    ) {
        if (summary.balancedPendingClosings.isEmpty()) {
            Text(
                "Sin cierres cuadrados pendientes en el período ($periodLabel).",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            summary.balancedPendingClosings.forEach { closing ->
                CashClosingDetailRow(
                    closing = closing,
                    viewModel = viewModel,
                    showDifference = false,
                    canReview = canReview,
                    showNewBadge = true
                )
                Spacer(Modifier.height(10.dp))
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    AccentSectionCard(
        title = "Cierre de Cajas con Alertas de Diferencias",
        accentColor = BrandWarning,
        titleTrailing = {
            if (summary.differencePendingClosings.isNotEmpty()) {
                PendingBellBadge(count = summary.differencePendingClosings.size)
            }
        }
    ) {
        if (summary.differencePendingClosings.isEmpty()) {
            Text(
                "Sin cierres con diferencias pendientes en el período ($periodLabel).",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            summary.differencePendingClosings.forEach { closing ->
                CashClosingDetailRow(
                    closing = closing,
                    viewModel = viewModel,
                    showDifference = true,
                    canReview = canReview,
                    showNewBadge = true
                )
                Spacer(Modifier.height(10.dp))
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    AccentSectionCard(
        title = "Cierre de cajas contabilizados y aprobados",
        accentColor = BrandSuccess,
        titleColor = BrandSuccess
    ) {
        if (summary.approvedClosings.isEmpty()) {
            Text(
                "Sin cierres aprobados en el período ($periodLabel).",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            summary.approvedClosings.forEach { closing ->
                CashClosingDetailRow(
                    closing = closing,
                    viewModel = viewModel,
                    showDifference = closing.hasDifference,
                    canReview = false,
                    canRevert = canReview,
                    showNewBadge = false
                )
                Spacer(Modifier.height(10.dp))
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    AccentSectionCard(
        title = "Cierre de caja rechazados",
        accentColor = BrandOutflow,
        titleColor = BrandOutflow
    ) {
        if (summary.rejectedClosings.isEmpty()) {
            Text(
                "Sin cierres rechazados en el período ($periodLabel).",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            summary.rejectedClosings.forEach { closing ->
                CashClosingDetailRow(
                    closing = closing,
                    viewModel = viewModel,
                    showDifference = closing.hasDifference,
                    canReview = false,
                    showNewBadge = false
                )
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

private fun formatApprovedRatesLabel(
    closings: List<CashClosingRecord>,
    viewModel: ReportsViewModel
): String {
    val rates = closings.map { it.rate }.distinct()
    return when {
        rates.isEmpty() -> "—"
        rates.size == 1 -> "Bs ${viewModel.formatRate(rates.first())}"
        else -> "Varias tasas (${rates.size})"
    }
}

private fun closingSucursalLabel(closing: CashClosingRecord): String =
    closing.userSucursal.displaySucursalOrPending().let { label ->
        if (label != PENDING_SUCURSAL_LABEL) return label
        closing.branchName.takeIf { it.isNotBlank() } ?: label
    }

@Composable
private fun PendingBellBadge(count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Default.Notifications,
            contentDescription = "Cierres pendientes",
            tint = BrandWarning,
            modifier = Modifier.size(20.dp)
        )
        if (count > 0) {
            Spacer(Modifier.size(4.dp))
            Text(
                text = "$count",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = BrandWarning
            )
        }
    }
}

@Composable
private fun CashClosingDetailRow(
    closing: CashClosingRecord,
    viewModel: ReportsViewModel,
    showDifference: Boolean,
    canReview: Boolean,
    canRevert: Boolean = false,
    showNewBadge: Boolean
) {
    var reviewAction by remember { mutableStateOf<ClosingReviewAction?>(null) }
    var showDetail by remember { mutableStateOf(false) }

    if (showDetail) {
        CashClosingDetailDialog(
            closing = closing,
            viewModel = viewModel,
            onDismiss = { showDetail = false }
        )
    }

    if (reviewAction != null) {
        ClosingReviewConfirmDialog(
            action = reviewAction!!,
            onDismiss = { reviewAction = null },
            onConfirm = {
                viewModel.reviewClosing(closing.id, reviewAction!!)
                reviewAction = null
            }
        )
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showNewBadge && closing.status == CashClosingStatus.PENDING) {
                    Icon(
                        Icons.Default.Notifications,
                        contentDescription = "Nuevo cierre",
                        tint = BrandWarning,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.size(6.dp))
                }
                Text(
                    text = "${closing.dateText} · ${closing.username}",
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            ClosingStatusPill(status = closing.status)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = closingSucursalLabel(closing),
            style = MaterialTheme.typography.bodySmall,
            color = if (closing.userSucursal.isBlank() && closing.branchName.isBlank()) {
                BrandWarning
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        if (closing.userSucursal.isNotBlank() && closing.branchName.isNotBlank() &&
            closing.userSucursal != closing.branchName
        ) {
            Text(
                text = "Local del cierre: ${closing.branchName}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(4.dp))
        ReportKeyValueRow(
            label = "Cuadre",
            value = "${viewModel.formatUsd(closing.grandTotalUsd)} · ${viewModel.formatBs(closing.grandTotalBs)}",
            valueColor = MaterialTheme.colorScheme.onSurface
        )
        ReportKeyValueRow(
            label = "Tasa del día",
            value = "Bs ${viewModel.formatRate(closing.rate)}",
            valueColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val salesDiscount = closing.displaySalesDiscountUsd()
        ReportKeyValueRow(
            label = if (salesDiscount > 0) "Ventas netas" else "Ventas del día",
            value = viewModel.formatUsd(closing.salesUsd),
            valueColor = MaterialTheme.colorScheme.onSurface
        )
        if (salesDiscount > 0) {
            ReportKeyValueRow(
                label = "Bruto / descuentos",
                value = "${viewModel.formatUsd(closing.displaySalesGrossUsd())} / -${viewModel.formatUsd(salesDiscount)}",
                valueColor = BrandSuccess
            )
        }
        if (showDifference) {
            Text(
                text = "Dif. ${viewModel.formatUsd(abs(closing.differenceUsd))} · " +
                    "Ventas ${viewModel.formatUsd(closing.salesUsd)} vs cuadre " +
                    viewModel.formatUsd(closing.grandTotalUsd),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold
            )
        }
        if (closing.revisionNumber > 1) {
            Text(
                text = "Intento #${closing.revisionNumber}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (canReview && closing.status == CashClosingStatus.PENDING) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showDetail = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Ver detalle del cierre")
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { reviewAction = ClosingReviewAction.APPROVE },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandSuccess)
                ) {
                    Text("Aprobar")
                }
                OutlinedButton(
                    onClick = { reviewAction = ClosingReviewAction.REJECT },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = BrandOutflow)
                ) {
                    Text("Rechazar")
                }
            }
        } else if (closing.detailSnapshot.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showDetail = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Ver detalle del cierre")
            }
        }
        if (canRevert && closing.status == CashClosingStatus.APPROVED) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { reviewAction = ClosingReviewAction.REVERT },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = BrandOutflow)
            ) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Revertir cierre")
            }
        }
    }
}

@Composable
private fun ClosingStatusPill(status: CashClosingStatus) {
    val (label, color) = when (status) {
        CashClosingStatus.PENDING -> "Pendiente" to BrandWarning
        CashClosingStatus.APPROVED -> "Aprobado" to BrandSuccess
        CashClosingStatus.REJECTED -> "Rechazado" to BrandOutflow
        CashClosingStatus.REVERTED -> "Revertido" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    StatusPill(text = label, color = color)
}

@Composable
private fun ClosingReviewConfirmDialog(
    action: ClosingReviewAction,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val title = when (action) {
        ClosingReviewAction.APPROVE -> "Aprobar cierre de caja"
        ClosingReviewAction.REJECT -> "Rechazar cierre de caja"
        ClosingReviewAction.REVERT -> "Revertir cierre de caja"
    }
    val description = when (action) {
        ClosingReviewAction.APPROVE ->
            "Confirma que deseas aprobar y contabilizar este cierre."
        ClosingReviewAction.REJECT ->
            "Confirma que deseas rechazar este cierre. El usuario deberá volver a ejecutarlo."
        ClosingReviewAction.REVERT ->
            "Confirma que deseas revertir este cierre aprobado. " +
                "Se descontará de Recaudación Total y el usuario podrá registrar un nuevo cierre del día."
    }
    val checkLabel = when (action) {
        ClosingReviewAction.APPROVE -> "Confirmo que quiero aprobar este cierre."
        ClosingReviewAction.REJECT -> "Confirmo que quiero rechazar este cierre."
        ClosingReviewAction.REVERT -> "Confirmo que quiero revertir este cierre."
    }
    val confirmLabel = when (action) {
        ClosingReviewAction.APPROVE -> "Aprobar"
        ClosingReviewAction.REJECT -> "Rechazar"
        ClosingReviewAction.REVERT -> "Revertir"
    }

    ConfirmCheckDialog(
        title = title,
        description = description,
        checkLabel = checkLabel,
        confirmLabel = confirmLabel,
        onDismiss = onDismiss,
        onConfirm = onConfirm
    )
}

@Composable
private fun KpiCard(
    title: String,
    lines: List<String>,
    footer: (@Composable () -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            lines.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodyLarge)
            }
            footer?.let {
                Spacer(Modifier.height(12.dp))
                it()
            }
        }
    }
}

@Composable
private fun ApprovedClosingsBreakdownDialog(
    closings: List<CashClosingRecord>,
    viewModel: ReportsViewModel,
    onDismiss: () -> Unit
) {
    val sortedClosings = remember(closings) {
        closings.sortedByDescending { it.closedAt }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Recaudación por cierre") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                sortedClosings.forEachIndexed { index, closing ->
                    if (index > 0) {
                        Spacer(Modifier.height(10.dp))
                        ReportDivider()
                        Spacer(Modifier.height(10.dp))
                    }
                    Text(
                        text = closing.dateText,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${viewModel.formatUsd(closing.grandTotalUsd)} · " +
                            viewModel.formatBs(closing.grandTotalBs),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Tasa del día: Bs ${viewModel.formatRate(closing.rate)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cerrar")
            }
        }
    )
}

@Composable
private fun CashClosingDetailDialog(
    closing: CashClosingRecord,
    viewModel: ReportsViewModel,
    onDismiss: () -> Unit
) {
    val snapshot = remember(closing.detailSnapshot) {
        CashClosingSnapshotCodec.decode(closing.detailSnapshot)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Detalle del cierre", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                ReportHeader(
                    title = closing.dateText,
                    subtitle = "${closing.username} · ${closingSucursalLabel(closing)}"
                )
                Spacer(Modifier.height(8.dp))
                ReportKeyValueRow(
                    label = "Tasa del día",
                    value = "Bs ${viewModel.formatRate(closing.rate)}"
                )
                if (snapshot == null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Detalle no disponible para cierres registrados antes de esta actualización.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    ReportKeyValueRow(
                        label = "Ventas del día",
                        value = "${viewModel.formatUsd(closing.salesUsd)} · ${viewModel.formatBs(closing.salesBs)}"
                    )
                    val fallbackDiscount = closing.displaySalesDiscountUsd()
                    if (fallbackDiscount > 0) {
                        ReportKeyValueRow(
                            label = "Ventas brutas",
                            value = viewModel.formatUsd(closing.displaySalesGrossUsd())
                        )
                        ReportKeyValueRow(
                            label = "Descuentos",
                            value = "-${viewModel.formatUsd(fallbackDiscount)}",
                            valueColor = BrandSuccess
                        )
                    }
                    ReportKeyValueRow(
                        label = "Cuadre total",
                        value = "${viewModel.formatUsd(closing.grandTotalUsd)} · ${viewModel.formatBs(closing.grandTotalBs)}"
                    )
                    if (closing.observations.isNotBlank()) {
                        ReportKeyValueRow(label = "Observaciones", value = closing.observations)
                    }
                } else {
                    if (snapshot.prevCashUsd > 0) {
                        Spacer(Modifier.height(8.dp))
                        ReportKeyValueRow(
                            label = "Efectivo cierre anterior",
                            value = "${viewModel.formatUsd(snapshot.prevCashUsd)} · ${viewModel.formatBs(snapshot.prevCashBs)}"
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    ReportDivider(label = "Ventas del día")
                    Spacer(Modifier.height(6.dp))
                    val salesDiscount = snapshot.salesDiscountUsd.takeIf { it > 0 }
                        ?: closing.displaySalesDiscountUsd()
                    val salesGross = snapshot.salesGrossUsd.takeIf { it > 0 }
                        ?: closing.displaySalesGrossUsd()
                    if (salesDiscount > 0) {
                        ReportKeyValueRow(
                            label = "Ventas brutas",
                            value = "${viewModel.formatUsd(salesGross)} · ${viewModel.formatBs(salesGross * closing.rate)}"
                        )
                        ReportKeyValueRow(
                            label = "Descuentos",
                            value = "-${viewModel.formatUsd(salesDiscount)}",
                            valueColor = BrandSuccess
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    ReportTotalBanner(
                        label = if (salesDiscount > 0) "Ventas netas" else "Total ventas",
                        usd = viewModel.formatUsd(snapshot.salesUsd),
                        bs = viewModel.formatBs(snapshot.salesBs),
                        highlight = false
                    )
                    if (snapshot.confirmedOrders.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        ReportDivider(label = "Pedidos confirmados")
                        Spacer(Modifier.height(6.dp))
                        snapshot.confirmedOrders.forEach { order ->
                            val orderLabel = if (order.orderNumber > 0) {
                                "Pedido Nº ${order.orderNumber}"
                            } else {
                                "Pedido"
                            }
                            val value = if (order.discountUsd > 0) {
                                "${viewModel.formatUsd(order.totalUsd)} (bruto ${viewModel.formatUsd(order.subtotalUsd)}, desc. -${viewModel.formatUsd(order.discountUsd)})"
                            } else {
                                viewModel.formatUsd(order.totalUsd)
                            }
                            ReportKeyValueRow(label = orderLabel, value = value)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    ReportDivider(label = "Puntos de venta (A)")
                    Spacer(Modifier.height(6.dp))
                    if (snapshot.posEntries.isEmpty()) {
                        Text("— sin registros —", style = MaterialTheme.typography.bodySmall)
                    } else {
                        snapshot.posEntries.forEach { entry ->
                            ReportKeyValueRow(
                                label = entry.name,
                                value = "${viewModel.formatUsd(entry.usd)} · ${viewModel.formatBs(entry.bs)}"
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    ReportDivider(label = "Pago móvil (B)")
                    Spacer(Modifier.height(6.dp))
                    if (snapshot.mobileEntries.isEmpty()) {
                        Text("— sin registros —", style = MaterialTheme.typography.bodySmall)
                    } else {
                        snapshot.mobileEntries.forEach { entry ->
                            ReportKeyValueRow(
                                label = "Ref ${entry.ref.ifBlank { "—" }}",
                                value = "${viewModel.formatUsd(entry.usd)} · ${viewModel.formatBs(entry.bs)}"
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    ReportDivider(label = "Efectivo (C)")
                    Spacer(Modifier.height(6.dp))
                    if (snapshot.cashEntries.isEmpty()) {
                        ReportKeyValueRow(
                            label = "Total",
                            value = "${viewModel.formatUsd(snapshot.cashUsd)} · ${viewModel.formatBs(snapshot.cashBs)}"
                        )
                    } else {
                        snapshot.cashEntries.forEach { entry ->
                            ReportKeyValueRow(
                                label = entry.description.ifBlank { "Efectivo" },
                                value = "${viewModel.formatUsd(entry.usd)} · ${viewModel.formatBs(entry.bs)}"
                            )
                        }
                        ReportKeyValueRow(
                            label = "Subtotal C",
                            value = "${viewModel.formatUsd(snapshot.cashUsd)} · ${viewModel.formatBs(snapshot.cashBs)}"
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    ReportDivider(label = "Salidas (D)")
                    Spacer(Modifier.height(6.dp))
                    if (snapshot.expenseEntries.isEmpty()) {
                        Text("— sin registros —", style = MaterialTheme.typography.bodySmall)
                    } else {
                        snapshot.expenseEntries.forEach { entry ->
                            ReportKeyValueRow(
                                label = entry.description.ifBlank { "—" },
                                value = viewModel.formatUsd(entry.usd)
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    ReportDivider(label = "Cashea (E)")
                    Spacer(Modifier.height(6.dp))
                    ReportKeyValueRow(label = "Total", value = viewModel.formatUsd(snapshot.casheaUsd))
                    Spacer(Modifier.height(12.dp))
                    ReportTotalBanner(
                        label = "Total cuadre (A+B+C+D+E)",
                        usd = viewModel.formatUsd(closing.grandTotalUsd),
                        bs = viewModel.formatBs(closing.grandTotalBs),
                        highlight = true
                    )
                    if (closing.hasDifference) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Dif. ${viewModel.formatUsd(abs(closing.differenceUsd))} · " +
                                "Ventas ${viewModel.formatUsd(closing.salesUsd)} vs cuadre " +
                                viewModel.formatUsd(closing.grandTotalUsd),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    if (snapshot.observations.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        ReportKeyValueRow(label = "Observaciones", value = snapshot.observations)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cerrar")
            }
        }
    )
}
