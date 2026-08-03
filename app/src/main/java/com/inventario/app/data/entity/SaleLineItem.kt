package com.inventario.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sale_line_items",
    indices = [Index("saleRecordId"), Index("productId"), Index("createdAt")]
)
data class SaleLineItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val saleRecordId: Long,
    val productId: Long,
    val description: String,
    val quantity: Double,
    val unit: String,
    val unitPriceUsd: Double,
    val totalUsd: Double,
    val createdAt: Long
)
