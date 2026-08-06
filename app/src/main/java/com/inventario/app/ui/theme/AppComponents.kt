package com.inventario.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inventario.app.data.entity.ConfirmedOrderPreview
import com.inventario.app.data.entity.SaleLineItem

@Composable
fun AppScreenBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val top = MaterialTheme.colorScheme.background
    val bottom = MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)
    Box(
        modifier = modifier.background(
            Brush.verticalGradient(listOf(top, bottom))
        )
    ) {
        content()
    }
}

@Composable
fun AccentSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    titleColor: Color = MaterialTheme.colorScheme.primary,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    titleTrailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .width(5.dp)
                    .fillMaxHeight()
                    .background(
                        accentColor,
                        RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp)
                    )
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = title,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .padding(end = if (titleTrailing != null) 8.dp else 0.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = titleColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (titleTrailing != null) {
                        titleTrailing()
                    }
                }
                Spacer(Modifier.height(10.dp))
                content()
            }
        }
    }
}

@Composable
fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.14f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun BcvRateBanner(
    label: String,
    refreshing: Boolean = false,
    modifier: Modifier = Modifier
) {
    val hasRate = label.isNotBlank() && !label.endsWith("—")
    val rateText = label.removePrefix("Tasa BCV: ").ifBlank { "—" }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(text = "💱", fontSize = 22.sp)
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Tasa del día (BCV)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = rateText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (hasRate) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            if (refreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/**
 * Diálogo de confirmación para acciones sensibles (aprobar/rechazar/revertir)
 * que reemplaza la antigua clave de acceso numérica por un check explícito.
 */
@Composable
fun ConfirmCheckDialog(
    title: String,
    description: String,
    checkLabel: String,
    confirmLabel: String = "Confirmar",
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    var checked by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(description, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { checked = !checked }
                ) {
                    Checkbox(checked = checked, onCheckedChange = { checked = it })
                    Spacer(Modifier.width(4.dp))
                    Text(checkLabel, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = checked
            ) {
                Text(confirmLabel)
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
fun ReportHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun ReportDivider(
    label: String? = null,
    modifier: Modifier = Modifier
) {
    if (label == null) {
        HorizontalDivider(modifier = modifier, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
    } else {
        Row(
            modifier = modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outline)
            Text(
                text = label,
                modifier = Modifier.padding(horizontal = 10.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
            HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
fun ReportKeyValueRow(
    label: String,
    value: String,
    bold: Boolean = false,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = if (isCompactWidth()) 2 else 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
            color = valueColor,
            textAlign = TextAlign.End
        )
    }
}

@Composable
fun ReportTotalBanner(
    label: String,
    usd: String,
    bs: String? = null,
    modifier: Modifier = Modifier,
    highlight: Boolean = true
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (highlight) {
                    Brush.horizontalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        )
                    )
                } else {
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        )
                    )
                }
            )
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = usd,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            if (bs != null) {
                Text(
                    text = bs,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
fun ReportMetaChip(
    icon: String,
    text: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val chipModifier = if (onClick != null) {
        modifier.clickable(onClick = onClick)
    } else {
        modifier
    }
    Surface(
        modifier = chipModifier,
        shape = RoundedCornerShape(10.dp),
        color = if (highlight) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = icon, fontSize = 14.sp)
            Spacer(Modifier.width(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Medium,
                color = if (highlight) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

fun confirmedOrdersLabel(count: Int): String =
    "$count pedido${if (count == 1) "" else "s"} confirmado${if (count == 1) "" else "s"} hoy"

@Composable
fun ConfirmedOrdersBanner(
    count: Int,
    onReset: (() -> Unit)? = null,
    onPreview: (() -> Unit)? = null,
    resetting: Boolean = false,
    modifier: Modifier = Modifier
) {
    var showConfirm by remember { mutableStateOf(false) }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ReportMetaChip(
            icon = "🧾",
            text = confirmedOrdersLabel(count),
            highlight = true,
            onClick = if (count > 0) onPreview else null,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (onReset != null) {
            if (resetting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
            } else {
                TextButton(
                    onClick = { showConfirm = true },
                    enabled = count > 0
                ) {
                    Text("Reiniciar")
                }
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Reiniciar contador del día") },
            text = {
                Text(
                    "Se borrará el registro de los $count pedido${if (count == 1) "" else "s"} " +
                        "confirmado${if (count == 1) "" else "s"} hoy y el total de ventas precargado. " +
                        "El inventario descontado no se restaura."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirm = false
                        onReset?.invoke()
                    }
                ) {
                    Text("Reiniciar", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text("Cancelar")
                }
            }
        )
    }
}

@Composable
fun ConfirmedOrdersPreviewDialog(
    orders: List<ConfirmedOrderPreview>,
    loading: Boolean,
    error: String?,
    currentBcvRate: Double?,
    formatPrice: (Double) -> String,
    formatQty: (Double) -> String,
    formatMoney: (Double) -> String,
    formatTime: (Long) -> String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Pedidos confirmados hoy",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            when {
                loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                error != null -> {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                orders.isEmpty() -> {
                    Text(
                        text = "No hay pedidos confirmados hoy.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        itemsIndexed(orders, key = { _, order -> order.syncId }) { index, order ->
                            ConfirmedOrderPreviewCard(
                                index = index + 1,
                                order = order,
                                currentBcvRate = currentBcvRate,
                                formatPrice = formatPrice,
                                formatQty = formatQty,
                                formatMoney = formatMoney,
                                formatTime = formatTime
                            )
                        }
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

@Composable
private fun ConfirmedOrderPreviewCard(
    index: Int,
    order: ConfirmedOrderPreview,
    currentBcvRate: Double?,
    formatPrice: (Double) -> String,
    formatQty: (Double) -> String,
    formatMoney: (Double) -> String,
    formatTime: (Long) -> String
) {
    val bcvRate = order.bcvRate.takeIf { it > 0 } ?: currentBcvRate
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Pedido $index · ${formatTime(order.createdAt)}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = formatPrice(order.totalUsd),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            if (bcvRate != null) {
                Text(
                    text = "Bs ${formatMoney(order.totalUsd * bcvRate)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (order.lines.isEmpty()) {
                Text(
                    text = "Sin detalle de productos.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                order.lines.forEach { line ->
                    ConfirmedOrderLineRow(
                        line = line,
                        formatQty = formatQty
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfirmedOrderLineRow(
    line: SaleLineItem,
    formatQty: (Double) -> String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = line.description,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "${formatQty(line.quantity)} ${line.unit}",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
