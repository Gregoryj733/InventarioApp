package com.inventario.app.data.entity

data class SaleLineItem(
    val saleSyncId: String,
    val description: String,
    val quantity: Double,
    val unit: String,
    val unitPriceUsd: Double,
    val totalUsd: Double
)

data class ConfirmedOrderPreview(
    val syncId: String,
    val createdAt: Long,
    val totalUsd: Double,
    val bcvRate: Double,
    val lines: List<SaleLineItem>
)
