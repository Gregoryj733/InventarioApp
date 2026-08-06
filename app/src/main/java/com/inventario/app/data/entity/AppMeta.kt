package com.inventario.app.data.entity

data class AppMeta(
    val id: Int = 1,
    val bcvRate: Double? = null,
    val bcvFetchedAt: Long? = null,
    val lastInventoryUpdateAt: Long? = null
)
