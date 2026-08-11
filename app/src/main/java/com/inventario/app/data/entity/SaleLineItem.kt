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
    val lines: List<SaleLineItem>
)
