package com.inventario.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4

@Fts4(contentEntity = Product::class)
@Entity(tableName = "products_fts")
data class ProductFts(
    @ColumnInfo(name = "description") val description: String
)
