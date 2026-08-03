package com.inventario.app.ui.reports

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.inventario.app.data.entity.CashClosingRecord
import com.inventario.app.data.repository.DailySalesPoint
import com.inventario.app.data.repository.ReportsRepository
import com.inventario.app.data.repository.ReportsSummary
import com.inventario.app.ui.theme.AccentSectionCard
import com.inventario.app.ui.theme.AppScreenBackground
import com.inventario.app.ui.theme.BrandAppTopBar
import com.inventario.app.ui.theme.BrandWarning
import com.inventario.app.ui.theme.ReportKeyValueRow
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

data class ReportsUiState(
    val startDateText: String = "",
    val endDateText: String = "",
    val loading: Boolean = true,
    val error: String? = null,
    val summary: ReportsSummary? = null,
    val bcvLabel: String = "Tasa BCV: —"
)

class ReportsViewModel(
    private val reportsRepository: ReportsRepository,
    private val bcvRateProvider: suspend () -> Double?
) : ViewModel() {
    private val _state = MutableStateFlow(ReportsUiState())
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
                _state.update {
                    it.copy(
                        loading = false,
                        summary = summary,
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

    fun formatUsd(value: Double): String = "$${moneyFormat.format(value)}"
    fun formatBs(value: Double): String = "Bs ${moneyFormat.format(value)}"

    private fun parseDate(text: String): Long? = runCatching {
        dateFormat.parse(text.trim())?.time
    }.getOrNull()

    private fun dayEndExclusive(dayStart: Long): Long =
        dayStart + TimeUnit.DAYS.toMillis(1)

    companion object {
        fun factory(
            reportsRepository: ReportsRepository,
            bcvRateProvider: suspend () -> Double?
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ReportsViewModel(reportsRepository, bcvRateProvider) as T
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
                        ReportsContent(summary = summary, viewModel = viewModel, bcvLabel = state.bcvLabel)
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
    bcvLabel: String
) {
    KpiCard(
        title = "Recaudación total",
        lines = listOf(
            viewModel.formatUsd(summary.totalSalesUsd),
            viewModel.formatBs(summary.totalSalesBs),
            "${summary.orderCount} pedidos · $bcvLabel"
        )
    )
    Spacer(Modifier.height(12.dp))
    KpiCard(
        title = "Producto más vendido",
        lines = summary.topProduct?.let {
            listOf(it.description, "${it.totalQuantity} unidades · ${viewModel.formatUsd(it.totalUsd)}")
        } ?: listOf("Sin datos en el rango seleccionado")
    )
    Spacer(Modifier.height(12.dp))
    KpiCard(
        title = "Producto menos vendido",
        lines = summary.leastProduct?.let {
            listOf(it.description, "${it.totalQuantity} unidades · ${viewModel.formatUsd(it.totalUsd)}")
        } ?: listOf("Sin datos en el rango seleccionado")
    )
    Spacer(Modifier.height(12.dp))
    AccentSectionCard(title = "Ventas por día") {
        if (summary.dailySales.isEmpty()) {
            Text("Sin ventas en el período.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            SalesBarChart(points = summary.dailySales, barColor = MaterialTheme.colorScheme.primary)
        }
    }
    Spacer(Modifier.height(12.dp))
    AccentSectionCard(title = "Ingresos por cierre de caja") {
        if (summary.cashClosings.isEmpty()) {
            Text("Sin cierres registrados.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            summary.cashClosings.forEach { closing ->
                CashClosingRow(closing = closing, viewModel = viewModel)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    AccentSectionCard(title = "Alertas de diferencias", accentColor = BrandWarning) {
        if (summary.differenceAlerts.isEmpty()) {
            Text("Sin cierres con diferencias.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            summary.differenceAlerts.forEach { alert ->
                Text(
                    text = "${alert.dateText} · ${alert.branchName} · Dif. ${viewModel.formatUsd(abs(alert.differenceUsd))}",
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Ventas ${viewModel.formatUsd(alert.salesUsd)} vs cuadre ${viewModel.formatUsd(alert.grandTotalUsd)}",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun KpiCard(title: String, lines: List<String>) {
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
        }
    }
}

@Composable
private fun CashClosingRow(
    closing: CashClosingRecord,
    viewModel: ReportsViewModel
) {
    ReportKeyValueRow(
        label = "${closing.dateText} · ${closing.branchName}",
        value = viewModel.formatUsd(closing.salesUsd),
        valueColor = MaterialTheme.colorScheme.onSurface
    )
    Text(
        text = "Cuadre ${viewModel.formatUsd(closing.grandTotalUsd)} · ${viewModel.formatBs(closing.grandTotalBs)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun SalesBarChart(
    points: List<DailySalesPoint>,
    barColor: Color
) {
    val maxValue = points.maxOfOrNull { it.totalUsd }?.takeIf { it > 0 } ?: 1.0
    val displayPoints = if (points.size > 14) points.takeLast(14) else points

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
        ) {
            val barWidth = size.width / (displayPoints.size * 1.6f)
            val gap = barWidth * 0.6f
            displayPoints.forEachIndexed { index, point ->
                val barHeight = (point.totalUsd / maxValue * size.height * 0.85f).toFloat()
                val left = index * (barWidth + gap)
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(left, size.height - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(6f, 6f)
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            displayPoints.forEach { point ->
                Text(
                    text = point.dayLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
