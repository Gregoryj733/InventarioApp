package com.inventario.app.ui.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
import com.inventario.app.data.entity.displaySucursalOrPending
import com.inventario.app.data.repository.ReportsRepository
import com.inventario.app.data.repository.ReportsSummary
import com.inventario.app.data.session.SessionManager
import com.inventario.app.ui.theme.AccentSectionCard
import com.inventario.app.ui.theme.AppScreenBackground
import com.inventario.app.ui.theme.BrandAppTopBar
import com.inventario.app.ui.theme.BrandOutflow
import com.inventario.app.ui.theme.BrandSuccess
import com.inventario.app.ui.theme.BrandWarning
import com.inventario.app.ui.theme.ReportDivider
import com.inventario.app.ui.theme.ReportHeader
import com.inventario.app.ui.theme.ReportKeyValueRow
import com.inventario.app.ui.theme.ReportTotalBanner
import com.inventario.app.ui.theme.StatusPill
import com.inventario.app.ui.theme.screenHorizontalPadding
import com.inventario.app.ui.theme.screenVerticalPadding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

enum class ClosingReviewAction {
    APPROVE,
    REJECT,
    REVERT
}

data class ReportsUiState(
    val startDateText: String = "",
    val endDateText: String = "",
    val loading: Boolean = true,
    val error: String? = null,
    val actionMessage: String? = null,
    val actionError: String? = null,
    val summary: ReportsSummary? = null,
    val bcvLabel: String = "Tasa BCV: —",
    val canReviewClosings: Boolean = false,
    val pendingClosingCount: Int = 0
)

class ReportsViewModel(
    private val reportsRepository: ReportsRepository,
    private val bcvRateProvider: suspend () -> Double?,
    private val sessionManager: SessionManager,
    private val userRole: UserRole
) : ViewModel() {
    private val _state = MutableStateFlow(ReportsUiState(canReviewClosings = userRole == UserRole.ADMIN))
    val state: StateFlow<ReportsUiState> = _state.asStateFlow()

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale("es", "VE"))
    private val moneyFormat = NumberFormat.getNumberInstance(Locale("es", "VE")).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }

    init {
        val cal = Calendar.getInstance()
        val end = cal.timeInMillis
        cal.add(Calendar.DAY_OF_MONTH, -30)
        val start = cal.timeInMillis
        _state.update {
            it.copy(
                startDateText = dateFormat.format(start),
                endDateText = dateFormat.format(end)
            )
        }
        load()
    }

    fun onStartDateChange(value: String) {
        _state.update { it.copy(startDateText = value, error = null) }
    }

    fun onEndDateChange(value: String) {
        _state.update { it.copy(endDateText = value, error = null) }
    }

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
                    it.copy(loading = false, error = err.message ?: "No se pudieron cargar los reportes.")
                }
            }
        }
    }

    fun reviewClosing(id: Long, action: ClosingReviewAction, verificationCode: String) {
        if (userRole != UserRole.ADMIN) {
            _state.update { it.copy(actionError = "Solo administradores pueden validar cierres.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(actionMessage = null, actionError = null) }
            val reviewer = sessionManager.username().orEmpty()
            val result = when (action) {
                ClosingReviewAction.APPROVE ->
                    reportsRepository.approveClosing(id, reviewer, verificationCode)
                ClosingReviewAction.REJECT ->
                    reportsRepository.rejectClosing(id, reviewer, verificationCode)
                ClosingReviewAction.REVERT ->
                    reportsRepository.revertClosing(id, reviewer, verificationCode)
            }
            result.onSuccess {
                val message = when (action) {
                    ClosingReviewAction.APPROVE -> "Cierre aprobado y contabilizado."
                    ClosingReviewAction.REJECT -> "Cierre rechazado. El usuario debe volver a ejecutarlo."
                    ClosingReviewAction.REVERT ->
                        "Cierre revertido. Ya no cuenta en Recaudación Total y el usuario puede registrar uno nuevo."
                }
                _state.update { it.copy(actionMessage = message) }
                load()
            }.onFailure { err ->
                _state.update {
                    it.copy(actionError = err.message ?: "No se pudo completar la acción.")
                }
            }
        }
    }

    fun formatUsd(value: Double): String = "$${moneyFormat.format(value)}"
    fun formatBs(value: Double): String = "Bs ${moneyFormat.format(value)}"
    fun formatRate(value: Double): String = moneyFormat.format(value)

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
            userRole: UserRole
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ReportsViewModel(
                    reportsRepository,
                    bcvRateProvider,
                    sessionManager,
                    userRole
                ) as T
            }
        }
    }
}

@Composable
fun ReportsScreen(
    viewModel: ReportsViewModel,
    subtitle: String,
    onBack: () -> Unit,
    onLogout: () -> Unit,
    onRefreshBcv: () -> Unit
) {
    val state by viewModel.state.collectAsState()

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
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = screenHorizontalPadding(), vertical = screenVerticalPadding())
            ) {
                Text(
                    text = "Reportes",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Histórico hasta ${ReportsRepository.MAX_RANGE_DAYS} días.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.startDateText,
                        onValueChange = viewModel::onStartDateChange,
                        label = { Text("Desde") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = state.endDateText,
                        onValueChange = viewModel::onEndDateChange,
                        label = { Text("Hasta") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
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
        }
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
            Text("Sin cierres cuadrados pendientes.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Text("Sin cierres con diferencias pendientes.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Text("Sin cierres aprobados en el período.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Text("Sin cierres rechazados en el período.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        VerificationCodeDialog(
            action = reviewAction!!,
            onDismiss = { reviewAction = null },
            onConfirm = { code ->
                viewModel.reviewClosing(closing.id, reviewAction!!, code)
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
private fun VerificationCodeDialog(
    action: ClosingReviewAction,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var code by remember { mutableStateOf("") }

    val title = when (action) {
        ClosingReviewAction.APPROVE -> "Aprobar cierre de caja"
        ClosingReviewAction.REJECT -> "Rechazar cierre de caja"
        ClosingReviewAction.REVERT -> "Revertir cierre de caja"
    }
    val description = when (action) {
        ClosingReviewAction.APPROVE ->
            "Ingresa el código de verificación para aprobar y contabilizar este cierre."
        ClosingReviewAction.REJECT ->
            "Ingresa el código de verificación para rechazar. El usuario deberá volver a ejecutar su cierre."
        ClosingReviewAction.REVERT ->
            "Ingresa el código de verificación para revertir este cierre aprobado. " +
                "Se descontará de Recaudación Total y el usuario podrá registrar un nuevo cierre del día."
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(description, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Código de verificación") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(code) },
                enabled = code.isNotBlank()
            ) {
                Text("Confirmar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
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
                    ReportTotalBanner(
                        label = "Total ventas",
                        usd = viewModel.formatUsd(snapshot.salesUsd),
                        bs = viewModel.formatBs(snapshot.salesBs),
                        highlight = false
                    )
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
                    ReportKeyValueRow(
                        label = "Total",
                        value = "${viewModel.formatUsd(snapshot.cashUsd)} · ${viewModel.formatBs(snapshot.cashBs)}"
                    )
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
