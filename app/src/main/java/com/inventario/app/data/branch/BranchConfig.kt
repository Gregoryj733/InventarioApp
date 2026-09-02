package com.inventario.app.data.branch

import com.inventario.app.data.sync.SyncConfig

data class BranchConfig(
    val id: String,
    /** Nombre de sucursal en el servidor (asignación de usuarios, cupones, etc.). */
    val label: String,
    val baseUrl: String,
    val apiKey: String,
    val firebaseTopic: String,
    /** Identificador de branding (`total_care`, `supra_parts`, …). Por defecto = [id]. */
    val theme: String = id,
    /** Etiqueta corta en chips y selectores (p. ej. S1 (Supra Parts)). */
    val displayLabel: String = label
) {
    val chipLabel: String
        get() = displayLabel.ifBlank { label }
    fun toSyncConfig(fallbackUrls: List<String> = emptyList()): SyncConfig =
        SyncConfig(
            baseUrl = baseUrl,
            apiKey = apiKey,
            fallbackUrls = fallbackUrls
        )

    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() &&
            (baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))
}

fun normalizeBranchLabel(label: String): String =
    label.trim().lowercase().replace(Regex("\\s+"), " ")

fun branchLabelsMatch(a: String, b: String): Boolean =
    normalizeBranchLabel(a) == normalizeBranchLabel(b)

/** Código corto del chip (p. ej. "S1" en "S1 (Supra Parts)"). */
fun branchShortCode(chipLabel: String): String? {
    val start = chipLabel.indexOf('(')
    if (start <= 0) return null
    return chipLabel.substring(0, start).trim().takeIf { it.isNotBlank() }
}

/** Acepta label, displayLabel, chipLabel, id de sucursal y alias heredados. */
fun sucursalMatchesBranch(sucursal: String, branch: BranchConfig): Boolean {
    if (sucursal.isBlank()) return false
    if (branchLabelsMatch(branch.label, sucursal)) return true
    if (branchLabelsMatch(branch.chipLabel, sucursal)) return true
    if (branchLabelsMatch(branch.displayLabel, sucursal)) return true
    if (normalizeBranchId(sucursal) == normalizeBranchId(branch.id)) return true
    if (normalizeBranchId(sucursal) == normalizeBranchId(branch.theme)) return true
    branchShortCode(branch.chipLabel)?.let { code ->
        if (branchLabelsMatch(code, sucursal)) return true
    }
    return legacyBranchAliases(branch.id).any { branchLabelsMatch(it, sucursal) }
}

private fun legacyBranchAliases(branchId: String): List<String> = when (normalizeBranchId(branchId)) {
    "total_care" -> listOf("Sucursal A", "sucursal_a")
    "supra_parts" -> listOf("Sucursal B", "sucursal_b", "Principal")
    else -> emptyList()
}
