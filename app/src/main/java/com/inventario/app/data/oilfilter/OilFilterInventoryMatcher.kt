package com.inventario.app.data.oilfilter

import com.inventario.app.data.battery.BatteryStockMatch
import com.inventario.app.data.cashea.CasheaCalculator
import com.inventario.app.data.entity.OilFilterEntry
import com.inventario.app.data.entity.Product
import com.inventario.app.data.search.ProductSearch
import java.text.Normalizer

data class OilFilterRecommendation(
    val entry: OilFilterEntry,
    val stockMatch: BatteryStockMatch,
    val otherStockFilters: List<Product> = emptyList(),
    val oilStock: List<Product> = emptyList()
)

/**
 * Índice en memoria del inventario para cruzar códigos WEB sin re-normalizar
 * descripciones en cada tecla.
 */
class OilFilterStockIndex(products: List<Product>) {
    private val items: List<IndexedProduct> = products.map(IndexedProduct::from)
    private val filterItems = items.filter { it.isFilter }
    private val oilItems = items.filter { it.isOil }

    fun recommend(entry: OilFilterEntry): OilFilterRecommendation {
        val codes = ArrayList<Pair<String, List<String>>>(1 + entry.alternativas.size + entry.equivalencias.size)
        codes.add(compact(entry.filtroCodigo) to tokens(entry.filtroCodigo))
        entry.alternativas.forEach { code -> codes.add(compact(code) to tokens(code)) }
        entry.equivalencias.forEach { code -> codes.add(compact(code) to tokens(code)) }

        val scored = ArrayList<Pair<IndexedProduct, Int>>(8)
        for (item in items) {
            val score = matchScore(item, codes) ?: continue
            scored.add(item to score)
        }
        scored.sortWith(
            compareByDescending<Pair<IndexedProduct, Int>> { it.first.product.quantity > 0 }
                .thenByDescending { it.second }
                .thenByDescending { it.first.product.quantity }
        )
        val best = scored.firstOrNull()?.first?.product
        val others = scored.asSequence()
            .map { it.first.product }
            .filter { it.id != best?.id && it.quantity > 0 }
            .distinctBy { it.id }
            .take(4)
            .toList()
        return OilFilterRecommendation(
            entry = entry,
            stockMatch = BatteryStockMatch(
                inStock = best != null && best.quantity > 0,
                product = best,
                priceMessage = best?.let(::priceMessageFor)
            ),
            otherStockFilters = others,
            oilStock = matchOils(entry.aceitesRecomendados)
        )
    }

    fun searchStockFilters(query: String, excludeIds: Set<Long>): List<Product> {
        if (query.trim().length < 2) return emptyList()
        return filterItems.asSequence()
            .filter { it.product.id !in excludeIds && it.product.quantity > 0 }
            .filter { ProductSearch.matchesAllTokens(it.product.description, query) }
            .map { it.product }
            .sortedByDescending { it.quantity }
            .take(8)
            .toList()
    }

    private fun matchOils(viscosities: List<String>): List<Product> {
        if (viscosities.isEmpty() || oilItems.isEmpty()) return emptyList()
        val compactVisc = viscosities.map { compact(it) }.filter { it.length >= 3 }
        if (compactVisc.isEmpty()) return emptyList()
        return oilItems.asSequence()
            .filter { it.product.quantity > 0 }
            .filter { item -> compactVisc.any { visc -> item.compact.contains(visc) } }
            .map { it.product }
            .sortedByDescending { it.quantity }
            .take(5)
            .toList()
    }

    private data class IndexedProduct(
        val product: Product,
        val compact: String,
        val tokens: List<String>,
        val isFilter: Boolean,
        val isOil: Boolean
    ) {
        companion object {
            fun from(product: Product): IndexedProduct {
                val compactDesc = compact(product.description)
                return IndexedProduct(
                    product = product,
                    compact = compactDesc,
                    tokens = tokens(product.description),
                    isFilter = isLikelyOilFilter(compactDesc),
                    isOil = isLikelyEngineOil(compactDesc, product.description)
                )
            }
        }
    }

    companion object {
        private val DIACRITICS = Regex("\\p{Mn}+")
        private val NON_ALNUM = Regex("[^A-Z0-9]+")
        private val W_CODE = Regex("W\\d{3,}")
        private val VISCOSITY = Regex("\\b\\d{1,2}W-?\\d{2}\\b", RegexOption.IGNORE_CASE)

        private fun matchScore(item: IndexedProduct, codes: List<Pair<String, List<String>>>): Int? {
            var best: Int? = null
            codes.forEachIndexed { index, (codeCompact, codeTokens) ->
                if (!matchesCode(item, codeCompact, codeTokens)) return@forEachIndexed
                val score = when {
                    index == 0 -> 100
                    index < 4 -> 70
                    else -> 40
                } + if (item.isFilter) 15 else 0
                best = maxOf(best ?: 0, score)
            }
            return best
        }

        private fun matchesCode(
            item: IndexedProduct,
            codeCompact: String,
            codeTokens: List<String>
        ): Boolean {
            if (codeCompact.length < 3) return false
            if (item.tokens.any { it == codeCompact }) return true
            if (codeTokens.size >= 2 && containsSequence(item.tokens, codeTokens)) return true
            val digits = codeCompact.filter { it.isDigit() }
            if (digits.length >= 4 && item.tokens.any { it == digits || it == codeCompact }) return true
            return codeCompact.length >= 5 && item.compact.contains(codeCompact)
        }

        private fun isLikelyOilFilter(compactDesc: String): Boolean {
            if (!compactDesc.contains("FILTRO")) return false
            return compactDesc.contains("ACEITE") ||
                compactDesc.contains("WEB") ||
                compactDesc.contains("WCH") ||
                W_CODE.containsMatchIn(compactDesc)
        }

        private fun isLikelyEngineOil(compactDesc: String, original: String): Boolean {
            if (compactDesc.contains("FILTRO")) return false
            if (!compactDesc.contains("ACEITE")) return false
            return compactDesc.contains("MOTOR") ||
                compactDesc.contains("LUBRIC") ||
                VISCOSITY.containsMatchIn(original)
        }

        private fun containsSequence(haystack: List<String>, needle: List<String>): Boolean {
            if (needle.isEmpty() || haystack.size < needle.size) return false
            for (start in 0..haystack.size - needle.size) {
                if (needle.indices.all { offset -> haystack[start + offset] == needle[offset] }) {
                    return true
                }
            }
            return false
        }

        private fun tokens(text: String): List<String> =
            text.uppercase()
                .replace(NON_ALNUM, " ")
                .trim()
                .split(' ')
                .filter { it.isNotEmpty() }

        private fun compact(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFD)
                .replace(DIACRITICS, "")
                .uppercase()
                .replace(NON_ALNUM, "")

        private fun priceMessageFor(product: Product): String =
            if (CasheaCalculator.isCasheaEligible(product.price)) {
                "Precio por Cashea"
            } else {
                "Precio al contacto"
            }
    }
}
