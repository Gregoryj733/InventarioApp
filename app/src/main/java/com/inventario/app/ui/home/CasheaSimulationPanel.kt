package com.inventario.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.inventario.app.data.cashea.CasheaCalculator
import com.inventario.app.ui.theme.AccentSectionCard
import com.inventario.app.ui.theme.BrandWarning
import com.inventario.app.ui.theme.ReportDivider
import com.inventario.app.ui.theme.ReportKeyValueRow

@Composable
fun CasheaSimulationPanel(
    simulation: CasheaCalculator.CasheaSimulation,
    formatPrice: (Double) -> String,
    formatMoney: (Double) -> String
) {
    val casheaEligible = CasheaCalculator.isCasheaEligible(simulation.baseUsd)
    val eligibilityMessage = CasheaCalculator.casheaEligibilityMessage(simulation.baseUsd)

    Spacer(Modifier.height(12.dp))
    AccentSectionCard(
        title = "Simulación Cashea",
        accentColor = BrandWarning
    ) {
        if (!casheaEligible && eligibilityMessage != null) {
            CasheaMinimumAlert(message = eligibilityMessage)
            Spacer(Modifier.height(10.dp))
        } else {
            CasheaEligibleBanner()
            Spacer(Modifier.height(10.dp))
        }
        Text(
            text = "Casos sin factura",
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))
        PaymentCaseCard(
            title = "Pago móvil / Punto",
            subtitle = "Monto bolívares sin IVA",
            usd = formatPrice(simulation.pagoMovilSinIvaUsd),
            bs = "Bs ${formatMoney(simulation.pagoMovilSinIvaBs)}",
            headerColor = Color(0xFFE8F5E9)
        )
        Spacer(Modifier.height(8.dp))
        PaymentCaseCard(
            title = "Pago Cashea",
            subtitle = if (casheaEligible) {
                "Monto bolívares con IVA (16%)"
            } else {
                "No disponible — mínimo $${CasheaCalculator.MINIMUM_PURCHASE_USD.toInt()} USD"
            },
            usd = formatPrice(simulation.pagoCasheaConIvaUsd),
            bs = "Bs ${formatMoney(simulation.pagoCasheaConIvaBs)}",
            headerColor = if (casheaEligible) Color(0xFFFFF9C4) else Color(0xFFFFEBEE),
            dimmed = !casheaEligible
        )
        if (casheaEligible) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Inicial por nivel (sobre total con IVA)",
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(6.dp))
            simulation.casheaLevels.forEach { level ->
                ReportKeyValueRow(
                    label = level.level.label,
                    value = "${formatPrice(level.usd)} · Bs ${formatMoney(level.bs)}",
                    valueColor = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Factura fiscal sin IVA",
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        FiscalRows(simulation.fiscalSinIva, formatPrice, formatMoney)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Factura fiscal con IVA",
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        FiscalRows(simulation.fiscalConIva, formatPrice, formatMoney)
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun CasheaMinimumAlert(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f)
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun CasheaEligibleBanner() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        )
    ) {
        Text(
            text = "✓ Monto elegible para Cashea (mínimo $${CasheaCalculator.MINIMUM_PURCHASE_USD.toInt()} USD)",
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun PaymentCaseCard(
    title: String,
    subtitle: String,
    usd: String,
    bs: String,
    headerColor: Color,
    dimmed: Boolean = false
) {
    val contentAlpha = if (dimmed) 0.55f else 1f
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = headerColor)
    ) {
        Column(
            modifier = Modifier.padding(
                start = 12.dp,
                top = 12.dp,
                end = 12.dp,
                bottom = 14.dp
            )
        ) {
            Text(
                title,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                usd,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error.copy(alpha = contentAlpha)
            )
            Text(
                bs,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)
            )
        }
    }
}

@Composable
private fun FiscalRows(
    fiscal: CasheaCalculator.FiscalBreakdown,
    formatPrice: (Double) -> String,
    formatMoney: (Double) -> String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        ReportKeyValueRow(
            label = "Total USD",
            value = formatPrice(fiscal.totalUsd),
            valueColor = MaterialTheme.colorScheme.error
        )
        ReportKeyValueRow(
            label = "Base imponible",
            value = "Bs ${formatMoney(fiscal.baseBs)}",
            valueColor = MaterialTheme.colorScheme.onSurface
        )
        ReportKeyValueRow(
            label = "IVA (16%)",
            value = "Bs ${formatMoney(fiscal.ivaBs)}",
            valueColor = MaterialTheme.colorScheme.onSurface
        )
        ReportDivider(label = "Monto")
        ReportKeyValueRow(
            label = "Total",
            value = "Bs ${formatMoney(fiscal.totalBs)}",
            valueColor = MaterialTheme.colorScheme.primary
        )
    }
}
