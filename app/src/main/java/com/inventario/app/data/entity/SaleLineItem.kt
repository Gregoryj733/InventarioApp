package com.inventario.app.data.entity

data class SaleLineItem(
    val saleSyncId: String,
    val description: String,
    val quantity: Double,
    val unit: String,
    val unitPriceUsd: Double,
    val totalUsd: Double,
    val casheaLevelLabel: String? = null,
    val casheaInitialUsd: Double? = null,
    val casheaInitialBs: Double? = null,
    val casheaPendingUsd: Double? = null,
    val casheaPendingBs: Double? = null,
    val casheaInstallments: Int? = null
) {
    val hasCashea: Boolean
        get() = casheaLevelLabel != null && casheaInitialUsd != null
}

data class ConfirmedOrderPreview(
    val syncId: String,
    val createdAt: Long,
    val totalUsd: Double,
    val bcvRate: Double,
    val lines: List<SaleLineItem>,
    val subtotalUsd: Double = totalUsd,
    val discountUsd: Double = 0.0
)

/** Descuento efectivo: usa el campo persistido o lo infiere del detalle de líneas. */
fun ConfirmedOrderPreview.effectiveDiscountUsd(): Double {
    if (discountUsd > 0.01) return discountUsd
    val linesGross = lines.sumOf { it.totalUsd }
    if (linesGross > totalUsd + 0.01) {
        return kotlin.math.round((linesGross - totalUsd) * 100.0) / 100.0
    }
    if (subtotalUsd > totalUsd + 0.01) {
        return kotlin.math.round((subtotalUsd - totalUsd) * 100.0) / 100.0
    }
    return 0.0
}

fun ConfirmedOrderPreview.effectiveSubtotalUsd(): Double {
    val discount = effectiveDiscountUsd()
    if (subtotalUsd > totalUsd + 0.01) return subtotalUsd
    if (discount > 0) return totalUsd + discount
    val linesGross = lines.sumOf { it.totalUsd }
    return if (linesGross > 0) linesGross else totalUsd
}

/** Números de pedido (1..N) ordenados por hora de confirmación. */
fun confirmedOrderNumbersBySyncId(orders: List<ConfirmedOrderPreview>): Map<String, Int> =
    orders
        .sortedBy { it.createdAt }
        .mapIndexed { index, order -> order.syncId to (index + 1) }
        .toMap()
