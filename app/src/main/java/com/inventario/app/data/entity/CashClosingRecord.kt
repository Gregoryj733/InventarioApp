package com.inventario.app.data.entity

data class CashClosingRecord(
    val id: Long = 0,
    val branchName: String,
    val dateText: String,
    val closedAt: Long,
    val rate: Double,
    val salesUsd: Double,
    /** Ventas brutas del día (antes de descuentos). */
    val salesGrossUsd: Double = 0.0,
    /** Descuentos aplicados en pedidos del día (USD). */
    val salesDiscountUsd: Double = 0.0,
    val salesBs: Double,
    val grandTotalUsd: Double,
    val grandTotalBs: Double,
    val differenceUsd: Double,
    val hasDifference: Boolean,
    val username: String,
    val observations: String = "",
    val status: CashClosingStatus = CashClosingStatus.PENDING,
    val revisionNumber: Int = 1,
    val reviewedBy: String = "",
    val reviewedAt: Long = 0L,
    val userSucursal: String = "",
    val detailSnapshot: String = ""
)

fun CashClosingRecord.displaySalesDiscountUsd(): Double {
    if (salesDiscountUsd > 0.0) return salesDiscountUsd
    return CashClosingSnapshotCodec.decode(detailSnapshot)?.salesDiscountUsd ?: 0.0
}

fun CashClosingRecord.displaySalesGrossUsd(): Double {
    if (salesGrossUsd > 0.0) return salesGrossUsd
    val snapshotGross = CashClosingSnapshotCodec.decode(detailSnapshot)?.salesGrossUsd ?: 0.0
    if (snapshotGross > 0.0) return snapshotGross
    return salesUsd + displaySalesDiscountUsd()
}
