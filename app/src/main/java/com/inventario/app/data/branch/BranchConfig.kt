package com.inventario.app.data.branch

import com.inventario.app.data.sync.SyncConfig

data class BranchConfig(
    val id: String,
    val label: String,
    val baseUrl: String,
    val apiKey: String,
    val firebaseTopic: String,
    /** Identificador de branding (`total_care`, `supra_parts`, …). Por defecto = [id]. */
    val theme: String = id
) {
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
