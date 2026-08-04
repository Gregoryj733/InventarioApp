package com.inventario.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sale_records")
data class SaleRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val syncId: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val totalUsd: Double,
    val bcvRate: Double = 0.0
)
