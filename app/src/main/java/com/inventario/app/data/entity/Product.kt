package com.inventario.app.data.entity

/**
 * Producto de inventario. Vive exclusivamente en la nube: `id` es un
 * identificador estable derivado de `syncId`, usado solo para referenciar el
 * producto dentro de la sesión actual (selección, líneas de pedido).
 */
data class Product(
    val id: Long = 0,
    val syncId: String = "",
    val description: String,
    val quantity: Double,
    val unit: String,
    val price: Double,
    val updatedAt: Long = System.currentTimeMillis()
)

fun stableProductId(syncId: String): Long = syncId.hashCode().toLong()
