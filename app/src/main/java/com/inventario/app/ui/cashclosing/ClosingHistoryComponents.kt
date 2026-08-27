package com.inventario.app.ui.cashclosing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.inventario.app.data.entity.CashClosingRecord
import com.inventario.app.data.entity.CashClosingStatus
import com.inventario.app.data.entity.displayLabel
import com.inventario.app.ui.theme.AccentSectionCard
import com.inventario.app.ui.theme.BrandSuccess
import com.inventario.app.ui.theme.BrandWarning
import com.inventario.app.ui.theme.StatusPill

data class ClosingHistoryPresentation(
    val todayDateText: String,
    val closings: List<CashClosingRecord>,
    val loading: Boolean,
    val canExport: Boolean,
    val exporting: Boolean,
    val periodLabel: String? = null,
    val groupByToday: Boolean = true
)

@Composable
fun ClosingHistorySection(
    presentation: ClosingHistoryPresentation,
    formatDateTime: (Long) -> String,
    formatPrice: (Double) -> String,
    onRefresh: () -> Unit,
    onExportExcel: () -> Unit
) {
    val todayClosings = presentation.closings.filter { it.dateText == presentation.todayDateText }
    val previousClosings = presentation.closings.filter { it.dateText != presentation.todayDateText }
    val helperText = if (presentation.groupByToday) {
        "Cierres de hoy y días anteriores. Usa el botón inferior para actualizar la lista."
    } else {
        "Filtra por rango de fechas y exporta el resultado a Excel."
    }

    AccentSectionCard(title = "Historial de cierres") {
        Text(
            helperText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (presentation.canExport && presentation.closings.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onExportExcel,
                enabled = !presentation.exporting && !presentation.loading,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                if (presentation.exporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text("Exportar Excel (.xlsx)")
            }
        }
        Spacer(Modifier.height(8.dp))
        if (presentation.loading) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            }
            return@AccentSectionCard
        }
        if (presentation.closings.isEmpty()) {
            Text(
                if (presentation.groupByToday) {
                    "Aún no hay cierres registrados en el servidor."
                } else {
                    "Sin cierres en el período seleccionado."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
            return@AccentSectionCard
        }
        if (!presentation.groupByToday) {
            presentation.periodLabel?.let { label ->
                Text("Período: $label", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
            }
            presentation.closings.forEach { closing ->
                ClosingHistoryRow(
                    closing = closing,
                    formatDateTime = formatDateTime,
                    formatPrice = formatPrice
                )
                Spacer(Modifier.height(6.dp))
            }
        } else {
        Text("Hoy (${presentation.todayDateText})", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        if (todayClosings.isEmpty()) {
            Text(
                "Sin cierres del día actual.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            todayClosings.forEach { closing ->
                ClosingHistoryRow(
                    closing = closing,
                    formatDateTime = formatDateTime,
                    formatPrice = formatPrice
                )
                Spacer(Modifier.height(6.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("Días anteriores", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        if (previousClosings.isEmpty()) {
            Text(
                "Sin cierres de días anteriores.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            previousClosings.take(40).forEach { closing ->
                ClosingHistoryRow(
                    closing = closing,
                    formatDateTime = formatDateTime,
                    formatPrice = formatPrice
                )
                Spacer(Modifier.height(6.dp))
            }
        }
        }
        OutlinedButton(
            onClick = onRefresh,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Actualizar historial")
        }
    }
}

@Composable
fun ClosingHistoryRow(
    closing: CashClosingRecord,
    formatDateTime: (Long) -> String,
    formatPrice: (Double) -> String
) {
    val statusColor = when (closing.status) {
        CashClosingStatus.PENDING -> BrandWarning
        CashClosingStatus.APPROVED -> BrandSuccess
        CashClosingStatus.REJECTED -> MaterialTheme.colorScheme.error
        CashClosingStatus.REVERTED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    formatDateTime(closing.closedAt),
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium
                )
                StatusPill(
                    text = closing.status.displayLabel(),
                    color = statusColor
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${closing.username} · ${closing.branchName.ifBlank { closing.userSucursal }.ifBlank { "Sin sucursal" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "${formatPrice(closing.grandTotalUsd)} · Diff ${formatPrice(closing.differenceUsd)}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
