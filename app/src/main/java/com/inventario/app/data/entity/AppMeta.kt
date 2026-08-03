package com.inventario.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_meta")
data class AppMeta(
    @PrimaryKey val id: Int = 1,
    val bcvRate: Double? = null,
    val bcvFetchedAt: Long? = null,
    val lastInventoryUpdateAt: Long? = null
)
