package com.inventario.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "products",
    indices = [Index(value = ["description"])]
)
data class Product(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val syncId: String = "",
    val description: String,
    val quantity: Double,
    val unit: String,
    val price: Double,
    val updatedAt: Long = System.currentTimeMillis()
)
