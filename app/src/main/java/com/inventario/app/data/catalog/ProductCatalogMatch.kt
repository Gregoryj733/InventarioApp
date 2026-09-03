package com.inventario.app.data.catalog

import com.inventario.app.data.entity.Product
import java.text.Normalizer
import java.util.Locale

/** Misma normalización que `normalizeProductDescription` en sync-server/server.js. */
fun normalizeProductDescription(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[\\u200B\\uFEFF]"), "")
        .replace(Regex("[＋⁺₊﹢]"), "+")
        .lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ")
        .replace(Regex("\\s*\\+\\s*"), "+")
        .trim()

/** Clave compacta para emparejar descripciones con espacios distintos (p. ej. "ATF+4" vs "ATF +4"). */
private fun compactProductDescriptionKey(text: String): String =
    normalizeProductDescription(text).replace(" ", "")

/** Ignora espacios, guiones y puntos (p. ej. "AVEO-CORSA" vs "AVEO CORSA"). */
internal fun looseCompactProductDescriptionKey(text: String): String =
    normalizeProductDescription(text).replace(Regex("[\\s\\-.]+"), "")

private fun descriptionsMatchByTokens(description: String, query: String): Boolean {
    val tokens = normalizeProductDescription(query).split(Regex("\\s+")).filter { it.isNotBlank() }
    if (tokens.isEmpty()) return false
    val normalizedDesc = normalizeProductDescription(description)
    return tokens.all { token -> normalizedDesc.contains(token) }
}

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
    val hadSyncId = productSyncId.isNotBlank()
    if (hadSyncId) {
        products.find { it.syncId == productSyncId }?.let { return it }
    } else if (productId != 0L) {
        products.find { it.id == productId }?.let { return it }
    }

    val normalized = normalizeProductDescription(description)
    if (normalized.isNotEmpty()) {
        val byNormalized = products.filter {
            normalizeProductDescription(it.description) == normalized
        }
        when (byNormalized.size) {
            1 -> return byNormalized.first()
            in 2..Int.MAX_VALUE -> return pickBestDescriptionMatch(byNormalized, description)
        }

        val compact = compactProductDescriptionKey(description)
        if (compact.isNotEmpty()) {
            val byCompact = products.filter {
                compactProductDescriptionKey(it.description) == compact
            }
            when (byCompact.size) {
                1 -> return byCompact.first()
                in 2..Int.MAX_VALUE -> {
                    return pickBestDescriptionMatch(byCompact, description)
                }
            }
        }

        val looseCompact = looseCompactProductDescriptionKey(description)
        if (looseCompact.isNotEmpty()) {
            val byLooseCompact = products.filter {
                looseCompactProductDescriptionKey(it.description) == looseCompact
            }
            when (byLooseCompact.size) {
                1 -> return byLooseCompact.first()
                in 2..Int.MAX_VALUE -> return pickBestDescriptionMatch(byLooseCompact, description)
            }
        }
    }

    val tokenMatches = products.filter { product ->
        product.description.equals(description, ignoreCase = true) ||
            descriptionsMatchByTokens(product.description, description)
    }
    return when (tokenMatches.size) {
        0 -> null
        1 -> tokenMatches.first()
        else -> pickBestDescriptionMatch(tokenMatches, description)
    }
}

private fun pickBestDescriptionMatch(candidates: List<Product>, description: String): Product =
    candidates.sortedWith(
        compareByDescending<Product> { it.description.equals(description, ignoreCase = true) }
            .thenByDescending { it.quantity }
    ).first()

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
