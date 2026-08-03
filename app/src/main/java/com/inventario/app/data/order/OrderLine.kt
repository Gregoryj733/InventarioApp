package com.inventario.app.data.order

data class OrderLine(
    val productId: Long,
    val description: String,
    val unit: String,
    val unitPriceUsd: Double,
    val quantity: Double
) {
    val totalUsd: Double = unitPriceUsd * quantity
}
