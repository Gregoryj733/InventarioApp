package com.inventario.app.data.search

import java.text.Normalizer

object ProductSearch {
    private val tokenSplit = Regex("\\s+")

    /**
     * Convierte texto libre a consulta FTS4 con coincidencia por prefijo.
     * Ej: "aceite kia" -> description:"aceite"* AND description:"kia"*
     */
    fun toFtsQuery(raw: String): String? {
        val tokens = tokenize(raw)
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" AND ") { token ->
            val escaped = token.replace("\"", "\"\"")
            "description:\"$escaped\"*"
        }
    }

    fun escapeLike(raw: String): String =
        raw.replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")

    fun tokenize(raw: String): List<String> =
        normalize(raw).split(tokenSplit).filter { it.isNotBlank() }

    fun matchesAllTokens(description: String, rawQuery: String): Boolean {
        val tokens = tokenize(rawQuery)
        if (tokens.isEmpty()) return false
        val normalizedDesc = normalize(description)
        return tokens.all { token -> normalizedDesc.contains(token) }
    }

    private fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
}
