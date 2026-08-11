package com.inventario.app.data.battery

/**
 * Extrae el amperaje de códigos de batería (p. ej. «49R-1100» → 1100) y de
 * descripciones de productos en inventario.
 */
object BatteryAmperage {

    private val CODE_INLINE_REGEX =
        Regex("""\b[A-Za-z0-9]+-(\d{2,4})\b""", RegexOption.IGNORE_CASE)
    private val AMP_SUFFIX_REGEX =
        Regex(
            """(\d{2,4})\s*(?:AMP(?:S|ER(?:S|AJE)?)?|AH|A\.?H\.?)\b""",
            RegexOption.IGNORE_CASE
        )

    fun fromBatteryCode(code: String): Int? {
        val trimmed = code.trim()
        val dash = trimmed.lastIndexOf('-')
        if (dash <= 0) return null
        return trimmed.substring(dash + 1).toIntOrNull()
    }

    fun fromProductDescription(description: String): Int? {
        CODE_INLINE_REGEX.find(description)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        AMP_SUFFIX_REGEX.find(description)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return null
    }
}
