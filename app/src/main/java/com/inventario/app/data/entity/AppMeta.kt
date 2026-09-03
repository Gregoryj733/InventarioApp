package com.inventario.app.data.entity

data class AppMeta(
    val id: Int = 1,
    val bcvRate: Double? = null,
    val bcvFetchedAt: Long? = null,
    /** Si es true, el admin fijó la tasa manualmente y el sistema no la actualiza automáticamente. */
    val bcvManualOverride: Boolean = false,
    val lastInventoryUpdateAt: Long? = null,
    val discountPercent: Double? = null
)
