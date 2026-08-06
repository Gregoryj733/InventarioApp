package com.inventario.app.data.entity

data class SaleRecord(
    val id: Long = 0,
    val syncId: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val totalUsd: Double,
    val bcvRate: Double = 0.0
)
