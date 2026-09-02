package com.inventario.app.data.entity

data class SaleRecord(
    val id: Long = 0,
    val syncId: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val totalUsd: Double,
    val bcvRate: Double = 0.0,
    /** Suma de líneas antes de descuentos; si no viene del servidor, usar [totalUsd] + [discountUsd]. */
    val subtotalUsd: Double = totalUsd,
    val discountUsd: Double = 0.0
)

fun SaleRecord.effectiveDiscountUsd(lines: List<SaleLineItem> = emptyList()): Double {
    if (discountUsd > 0.01) return discountUsd
    if (subtotalUsd > totalUsd + 0.01) {
        return kotlin.math.round((subtotalUsd - totalUsd) * 100.0) / 100.0
    }
    val linesGross = lines.sumOf { it.totalUsd }
    if (linesGross > totalUsd + 0.01) {
        return kotlin.math.round((linesGross - totalUsd) * 100.0) / 100.0
    }
    return 0.0
}

fun SaleRecord.effectiveSubtotalUsd(lines: List<SaleLineItem> = emptyList()): Double {
    val discount = effectiveDiscountUsd(lines)
    if (subtotalUsd > totalUsd + 0.01) return subtotalUsd
    if (discount > 0) return totalUsd + discount
    val linesGross = lines.sumOf { it.totalUsd }
    return if (linesGross > 0) linesGross else totalUsd
}
