package com.inventario.app.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.inventario.app.data.cashea.CasheaCalculator
import com.inventario.app.data.entity.SaleLineItem
import com.inventario.app.data.order.ProductPaymentChoice
import com.inventario.app.ui.theme.AccentSectionCard
import com.inventario.app.ui.theme.BrandWarning
import com.inventario.app.ui.theme.ReportDivider
import com.inventario.app.ui.theme.ReportKeyValueRow

@Composable
fun OrderCasheaPaymentSection(
    orderTotalUsd: Double,
    bcvRate: Double?,
    simulation: CasheaCalculator.CasheaSimulation?,
    paymentChoice: ProductPaymentChoice?,
    formatPrice: (Double) -> String,
    formatMoney: (Double) -> String,
    onPagoMovilSelected: () -> Unit,
    onCasheaLevelSelected: (CasheaCalculator.CasheaLevel) -> Unit,
    casheaDetail: CasheaCalculator.CasheaLineDetail? = null,
    modifier: Modifier = Modifier
) {
    val eligible = CasheaCalculator.isCasheaEligible(orderTotalUsd)

    Column(modifier = modifier.fillMaxWidth()) {
        Spacer(Modifier.height(10.dp))
        ReportDivider(label = if (eligible) "Forma de pago" else "Pago")
        Spacer(Modifier.height(8.dp))

        if (!eligible) {
            Text(
                text = "El pedido no califica para Cashea (mínimo $${CasheaCalculator.MINIMUM_PURCHASE_USD.toInt()} USD).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return
        }

        CasheaEligibleBanner()
        Spacer(Modifier.height(10.dp))

        if (simulation != null) {
            PaymentCaseCard(
                title = "Pago móvil / Punto",
                subtitle = "Monto bolívares sin IVA",
                usd = formatPrice(simulation.pagoMovilSinIvaUsd),
                bs = "Bs ${formatMoney(simulation.pagoMovilSinIvaBs)}",
                headerColor = Color(0xFFE8F5E9),
                selected = paymentChoice is ProductPaymentChoice.PagoMovil,
                onClick = onPagoMovilSelected
            )
            Spacer(Modifier.height(8.dp))
            PaymentCaseCard(
                title = "Pago Cashea",
                subtitle = "Monto bolívares con IVA (16%) — seleccione nivel",
                usd = formatPrice(simulation.pagoCasheaConIvaUsd),
                bs = "Bs ${formatMoney(simulation.pagoCasheaConIvaBs)}",
                headerColor = Color(0xFFFFF9C4),
                selected = paymentChoice is ProductPaymentChoice.Cashea
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Seleccione forma de pago",
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(6.dp))
            PaymentChoiceToggle(
                paymentChoice = paymentChoice,
                casheaLevels = simulation.casheaLevels,
                formatPrice = formatPrice,
                formatMoney = formatMoney,
                onPagoMovilSelected = onPagoMovilSelected,
                onCasheaLevelSelected = onCasheaLevelSelected,
                compact = true
            )
        }

        when {
            casheaDetail != null -> {
                Spacer(Modifier.height(8.dp))
                CasheaPaymentSummary(
                    detail = casheaDetail,
                    formatPrice = formatPrice,
                    formatMoney = formatMoney,
                    showChangeHint = true
                )
            }
            paymentChoice is ProductPaymentChoice.PagoMovil -> {
                Spacer(Modifier.height(8.dp))
                val totalBs = bcvRate?.let { orderTotalUsd * it }
                PagoMovilSelectedSummary(
                    totalUsd = formatPrice(orderTotalUsd),
                    totalBs = totalBs?.let { "Bs ${formatMoney(it)}" }
                )
            }
        }
    }
}

@Composable
fun CasheaSimulationPanel(
    simulation: CasheaCalculator.CasheaSimulation,
    formatPrice: (Double) -> String,
    formatMoney: (Double) -> String,
    paymentChoice: ProductPaymentChoice? = null,
    onPagoMovilSelected: (() -> Unit)? = null,
    onCasheaLevelSelected: ((CasheaCalculator.CasheaLevel) -> Unit)? = null
) {
    val selectable = onPagoMovilSelected != null && onCasheaLevelSelected != null
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
            headerColor = Color(0xFFE8F5E9),
            selected = selectable && paymentChoice is ProductPaymentChoice.PagoMovil,
            onClick = onPagoMovilSelected
        )
        Spacer(Modifier.height(8.dp))
        PaymentCaseCard(
            title = "Pago Cashea",
            subtitle = if (casheaEligible) {
                "Monto bolívares con IVA (16%) — seleccione nivel abajo"
            } else {
                "No disponible — mínimo $${CasheaCalculator.MINIMUM_PURCHASE_USD.toInt()} USD"
            },
            usd = formatPrice(simulation.pagoCasheaConIvaUsd),
            bs = "Bs ${formatMoney(simulation.pagoCasheaConIvaBs)}",
            headerColor = if (casheaEligible) Color(0xFFFFF9C4) else Color(0xFFFFEBEE),
            dimmed = !casheaEligible,
            selected = selectable && paymentChoice is ProductPaymentChoice.Cashea
        )
        if (casheaEligible && selectable) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Forma de pago (obligatorio)",
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Puede cambiar entre Pago móvil / Punto y Cashea en cualquier momento antes de agregar.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            PaymentChoiceToggle(
                paymentChoice = paymentChoice,
                casheaLevels = simulation.casheaLevels,
                formatPrice = formatPrice,
                formatMoney = formatMoney,
                onPagoMovilSelected = { onPagoMovilSelected?.invoke() },
                onCasheaLevelSelected = { onCasheaLevelSelected?.invoke(it) }
            )
        } else if (casheaEligible) {
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
        } else {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Referencia Cashea (no disponible por monto)",
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            simulation.casheaLevels.forEach { level ->
                ReportKeyValueRow(
                    label = level.level.label,
                    value = "${formatPrice(level.usd)} · Bs ${formatMoney(level.bs)}",
                    valueColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
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
fun CasheaIneligibleReadOnlySummary(
    simulation: CasheaCalculator.CasheaSimulation,
    formatPrice: (Double) -> String,
    formatMoney: (Double) -> String,
    modifier: Modifier = Modifier
) {
    val eligibilityMessage = CasheaCalculator.casheaEligibilityMessage(simulation.baseUsd)
        ?: "No califica para Cashea por el monto a pagar."

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Simulación Cashea (solo lectura)",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            CasheaMinimumAlert(message = eligibilityMessage)
            PaymentCaseCard(
                title = "Pago móvil / Punto",
                subtitle = "Forma de pago aplicada",
                usd = formatPrice(simulation.pagoMovilSinIvaUsd),
                bs = "Bs ${formatMoney(simulation.pagoMovilSinIvaBs)}",
                headerColor = Color(0xFFE8F5E9)
            )
            PaymentCaseCard(
                title = "Pago Cashea",
                subtitle = "No disponible — mínimo $${CasheaCalculator.MINIMUM_PURCHASE_USD.toInt()} USD",
                usd = formatPrice(simulation.pagoCasheaConIvaUsd),
                bs = "Bs ${formatMoney(simulation.pagoCasheaConIvaBs)}",
                headerColor = Color(0xFFFFEBEE),
                dimmed = true
            )
            Text(
                text = "Inicial por nivel (referencia)",
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            simulation.casheaLevels.forEach { level ->
                ReportKeyValueRow(
                    label = level.level.label,
                    value = "${formatPrice(level.usd)} · Bs ${formatMoney(level.bs)}",
                    valueColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
        }
    }
}

@Composable
fun SaleLineItemCasheaSummary(
    line: SaleLineItem,
    formatPrice: (Double) -> String,
    formatMoney: (Double) -> String,
    modifier: Modifier = Modifier
) {
    if (!line.hasCashea) return
    val initialUsd = line.casheaInitialUsd ?: return
    val initialBs = line.casheaInitialBs ?: return
    val pendingUsd = line.casheaPendingUsd ?: return
    val pendingBs = line.casheaPendingBs ?: return
    val levelLabel = line.casheaLevelLabel ?: return
    val installments = line.casheaInstallments ?: 2
    val totalWithIva = initialUsd + pendingUsd
    val pendingPercent = if (totalWithIva > 0) {
        kotlin.math.round((pendingUsd / totalWithIva) * 100).toInt()
    } else {
        0
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Pago con Cashea",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            CasheaSummaryLine(
                label = "Cashea $levelLabel — Pago inicial:",
                usd = formatPrice(initialUsd),
                bs = "Bs ${formatMoney(initialBs)}",
                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                valueColor = MaterialTheme.colorScheme.onSurface
            )
            CasheaSummaryLine(
                label = "Pendiente en $installments cuotas ($pendingPercent%):",
                usd = formatPrice(pendingUsd),
                bs = "Bs ${formatMoney(pendingBs)}",
                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                valueColor = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun PaymentChoiceToggle(
    paymentChoice: ProductPaymentChoice?,
    casheaLevels: List<CasheaCalculator.CasheaLevelAmount>,
    formatPrice: (Double) -> String,
    formatMoney: (Double) -> String,
    onPagoMovilSelected: () -> Unit,
    onCasheaLevelSelected: (CasheaCalculator.CasheaLevel) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp)
    ) {
        PaymentToggleChip(
            label = "Pago móvil / Punto",
            subtitle = null,
            selected = paymentChoice is ProductPaymentChoice.PagoMovil,
            onClick = onPagoMovilSelected,
            compact = compact,
            accentColor = Color(0xFFE8F5E9)
        )
        casheaLevels.forEach { level ->
            PaymentToggleChip(
                label = "Cashea ${level.level.label}",
                subtitle = "${formatPrice(level.usd)} · Bs ${formatMoney(level.bs)}",
                selected = paymentChoice is ProductPaymentChoice.Cashea &&
                    paymentChoice.level == level.level,
                onClick = { onCasheaLevelSelected(level.level) },
                compact = compact,
                accentColor = Color(0xFFFFF9C4)
            )
        }
    }
}

@Composable
private fun PaymentToggleChip(
    label: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
    compact: Boolean,
    accentColor: Color
) {
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) accentColor.copy(alpha = 0.95f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = if (compact) 10.dp else 12.dp,
                vertical = if (compact) 8.dp else 10.dp
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(if (compact) 18.dp else 20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
fun PagoMovilSelectedSummary(
    totalUsd: String,
    totalBs: String?,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFFE8F5E9).copy(alpha = 0.85f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Pago móvil / Punto",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = buildString {
                    append(totalUsd)
                    if (totalBs != null) append(" · $totalBs")
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Toque otra opción abajo para cambiar a Cashea.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun CasheaPaymentSummary(
    detail: CasheaCalculator.CasheaLineDetail,
    formatPrice: (Double) -> String,
    formatMoney: (Double) -> String,
    modifier: Modifier = Modifier,
    onPrimaryBackground: Boolean = false,
    showChangeHint: Boolean = false
) {
    val labelColor = if (onPrimaryBackground) {
        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.88f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val valueColor = if (onPrimaryBackground) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val accentColor = if (onPrimaryBackground) {
        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.95f)
    } else {
        MaterialTheme.colorScheme.primary
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = if (onPrimaryBackground) {
            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.12f)
        } else {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        }
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Pago con Cashea",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = accentColor
            )
            CasheaSummaryLine(
                label = "Cashea ${detail.level.label} — Pago inicial:",
                usd = formatPrice(detail.initialUsd),
                bs = "Bs ${formatMoney(detail.initialBs)}",
                labelColor = labelColor,
                valueColor = valueColor
            )
            CasheaSummaryLine(
                label = "Pendiente en ${detail.installmentCount} cuotas (${detail.pendingPercent}%):",
                usd = formatPrice(detail.pendingUsd),
                bs = "Bs ${formatMoney(detail.pendingBs)}",
                labelColor = labelColor,
                valueColor = valueColor
            )
            if (showChangeHint) {
                Text(
                    text = "Toque «Pago móvil / Punto» abajo para cambiar la forma de pago.",
                    style = MaterialTheme.typography.bodySmall,
                    color = labelColor
                )
            }
        }
    }
}

@Composable
private fun CasheaSummaryLine(
    label: String,
    usd: String,
    bs: String,
    labelColor: Color,
    valueColor: Color
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = labelColor
        )
        Text(
            text = "$usd · $bs",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = valueColor
        )
    }
}

@Composable
private fun CasheaLevelOption(
    label: String,
    value: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    }
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
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
    dimmed: Boolean = false,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val contentAlpha = if (dimmed) 0.55f else 1f
    val border = if (selected) {
        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    } else {
        null
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = headerColor),
        border = border
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
