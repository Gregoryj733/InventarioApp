package com.inventario.app.ui.battery

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.inventario.app.data.battery.BatteryStockMatch
import com.inventario.app.data.cashea.CasheaCalculator
import com.inventario.app.data.entity.Product
import com.inventario.app.data.order.ProductPaymentChoice
import com.inventario.app.ui.home.CasheaPaymentSummary
import com.inventario.app.ui.home.PagoMovilSelectedSummary
import com.inventario.app.ui.home.PaymentChoiceToggle
import java.text.DecimalFormat

private val moneyFormat = DecimalFormat("#,##0.00")

private fun formatPrice(value: Double): String = "$${moneyFormat.format(value)}"

private fun formatMoney(value: Double): String = moneyFormat.format(value)

@Composable
fun BatteryStockAvailabilitySection(
    stockMatch: BatteryStockMatch,
    bcvRate: Double?,
    modifier: Modifier = Modifier
) {
    Spacer(Modifier.height(12.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
    Spacer(Modifier.height(12.dp))
    Text(
        text = "Estado de disponibilidad: ${if (stockMatch.inStock) "En stock" else "No disponible"}",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = if (stockMatch.inStock) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.error
        },
        modifier = modifier
    )
    if (stockMatch.inStock) {
        stockMatch.product?.let { product ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = product.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Precio unitario: ${formatPrice(product.price)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            bcvRate?.let { rate ->
                Text(
                    text = "Equiv. BCV: Bs ${formatMoney(product.price * rate)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            BatteryPaymentPricingSection(
                product = product,
                bcvRate = bcvRate
            )
        }
    }
}

@Composable
private fun BatteryPaymentPricingSection(
    product: Product,
    bcvRate: Double?,
    modifier: Modifier = Modifier
) {
    var paymentChoice by remember(product.id) { mutableStateOf<ProductPaymentChoice?>(null) }

    val simulation = remember(product.price, bcvRate) {
        bcvRate?.let { CasheaCalculator.simulate(baseUsd = product.price, rate = it) }
    }
    val casheaEligible = CasheaCalculator.isCasheaEligible(product.price)

    Spacer(Modifier.height(12.dp))
    Text(
        text = "Forma de pago",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = "Seleccione un medio de pago para ver el cálculo del valor.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    when {
        bcvRate == null || simulation == null -> {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Tasa BCV no disponible. No se puede calcular el monto en bolívares.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        !casheaEligible -> {
            Spacer(Modifier.height(10.dp))
            PaymentChoiceToggle(
                paymentChoice = paymentChoice,
                casheaLevels = emptyList(),
                formatPrice = ::formatPrice,
                formatMoney = ::formatMoney,
                onPagoMovilSelected = { paymentChoice = ProductPaymentChoice.PagoMovil },
                onCasheaLevelSelected = {}
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = CasheaCalculator.casheaEligibilityMessage(product.price).orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        else -> {
            Spacer(Modifier.height(10.dp))
            PaymentChoiceToggle(
                paymentChoice = paymentChoice,
                casheaLevels = simulation.casheaLevels,
                formatPrice = ::formatPrice,
                formatMoney = ::formatMoney,
                onPagoMovilSelected = { paymentChoice = ProductPaymentChoice.PagoMovil },
                onCasheaLevelSelected = { level ->
                    paymentChoice = ProductPaymentChoice.Cashea(level)
                }
            )
        }
    }

    when (val choice = paymentChoice) {
        ProductPaymentChoice.PagoMovil -> {
            simulation?.let { sim ->
                Spacer(Modifier.height(10.dp))
                PagoMovilSelectedSummary(
                    totalUsd = formatPrice(sim.pagoMovilSinIvaUsd),
                    totalBs = "Bs ${formatMoney(sim.pagoMovilSinIvaBs)}"
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Pago al contado (Pago móvil / Punto) — sin IVA",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        is ProductPaymentChoice.Cashea -> {
            val rate = bcvRate ?: return
            val detail = CasheaCalculator.lineDetail(product.price, rate, choice.level)
            if (detail != null) {
                Spacer(Modifier.height(10.dp))
                CasheaPaymentSummary(
                    detail = detail,
                    formatPrice = ::formatPrice,
                    formatMoney = ::formatMoney
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Total con IVA (16%): ${formatPrice(detail.totalWithIvaUsd)} · " +
                        "Bs ${formatMoney(detail.totalWithIvaBs)}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        null -> Unit
    }
}

@Composable
fun BatteryHigherAmperageNote(modifier: Modifier = Modifier) {
    Spacer(Modifier.height(12.dp))
    Text(
        text = "Nota: Puede utilizarse una batería con mayor amperaje como alternativa, " +
            "aunque lo ideal es instalar el amperaje recomendado por el validador.",
        style = MaterialTheme.typography.bodySmall,
        fontStyle = FontStyle.Italic,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 4.dp)
    )
}
