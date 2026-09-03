package com.inventario.app.data.catalog

import com.inventario.app.data.entity.Product
import com.inventario.app.data.search.ProductSearch
import java.text.Normalizer
import java.util.Locale

/** Misma normalización que `normalizeProductDescription` en sync-server/server.js. */
fun normalizeProductDescription(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase(Locale.ROOT)
        .trim()

/**
 * Resuelve un producto del catálogo actual. Tras reimportar Excel los syncId
 * pueden cambiar en el servidor; la descripción normalizada actúa como ancla estable.
 */
fun findProductInCatalog(
    products: List<Product>,
    productSyncId: String,
    productId: Long,
    description: String
): Product? {
    if (productSyncId.isNotBlank()) {
        products.find { it.syncId == productSyncId }?.let { return it }
    }
    if (productId != 0L) {
        products.find { it.id == productId }?.let { return it }
    }

    val normalized = normalizeProductDescription(description)
    if (normalized.isNotEmpty()) {
        val byNormalized = products.filter {
            normalizeProductDescription(it.description) == normalized
        }
        when (byNormalized.size) {
            1 -> return byNormalized.first()
            in 2..Int.MAX_VALUE -> {
                return byNormalized.firstOrNull { it.description.equals(description, ignoreCase = true) }
                    ?: byNormalized.first()
            }
        }
    }

    val tokenMatches = products.filter { product ->
        product.description.equals(description, ignoreCase = true) ||
            ProductSearch.matchesAllTokens(product.description, description)
    }
    return when (tokenMatches.size) {
        0 -> null
        1 -> tokenMatches.first()
        else -> tokenMatches.firstOrNull { it.description.equals(description, ignoreCase = true) }
            ?: tokenMatches.first()
    }
}

/** Detecta si el catálogo del servidor difiere del caché local (syncId, precio o filas). */
fun inventoryCatalogChanged(previous: List<Product>, incoming: List<Product>): Boolean {
    if (previous.isEmpty() || incoming.isEmpty()) return previous != incoming
    if (previous.size != incoming.size) return true
    val previousByDescription = previous.associateBy { normalizeProductDescription(it.description) }
    for (product in incoming) {
        val key = normalizeProductDescription(product.description)
        val old = previousByDescription[key] ?: return true
        if (old.syncId != product.syncId || old.price != product.price || old.unit != product.unit) {
            return true
        }
    }
    return false
}
