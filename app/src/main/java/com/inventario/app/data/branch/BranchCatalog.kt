package com.inventario.app.data.branch

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Catálogo de sucursales definidas en [sync_config.json]. Cada sucursal apunta
 * a una instancia sync-server con su propia base de datos.
 */
class BranchCatalog(context: Context) {
    private val assetJson: JSONObject = runCatching {
        context.assets.open(ASSET_NAME).bufferedReader().use { reader ->
            JSONObject(reader.readText().trimStart('\uFEFF'))
        }
    }.getOrElse { JSONObject() }

    val fallbackUrls: List<String> =
        assetJson.optJSONArray("fallbackUrls")?.toStringList().orEmpty()

    val branches: List<BranchConfig> = loadBranches()

    val defaultBranch: BranchConfig?
        get() = branches.firstOrNull()

    fun findById(id: String): BranchConfig? =
        branches.firstOrNull { it.id == id }

    fun findByBaseUrl(url: String): BranchConfig? {
        val normalized = url.trim().trimEnd('/')
        return branches.firstOrNull { it.baseUrl.trimEnd('/') == normalized }
    }

    fun labelFor(branchId: String?): String {
        val normalized = normalizeBranchId(branchId)
        return findById(normalized)?.label.orEmpty()
    }

    fun displayLabelFor(branchId: String?): String {
        val normalized = normalizeBranchId(branchId)
        val branch = findById(normalized) ?: return ""
        return branch.chipLabel
    }

    fun configFor(branchId: String?): BranchConfig? =
        findById(normalizeBranchId(branchId))

    private fun loadBranches(): List<BranchConfig> {
        val fromArray = assetJson.optJSONArray("branches")?.toBranchList().orEmpty()
        if (fromArray.isNotEmpty()) return fromArray

        val legacyUrl = assetJson.optString("baseUrl", "").trim()
        val legacyKey = assetJson.optString("apiKey", "").trim()
        if (legacyUrl.isBlank()) return emptyList()
        return listOf(
            BranchConfig(
                id = LEGACY_BRANCH_ID,
                label = "Principal",
                baseUrl = legacyUrl,
                apiKey = legacyKey,
                firebaseTopic = DEFAULT_FIREBASE_TOPIC
            )
        )
    }

    companion object {
        const val ASSET_NAME = "sync_config.json"
        const val LEGACY_BRANCH_ID = "principal"
        const val DEFAULT_FIREBASE_TOPIC = "inventario_actualizado"
    }
}

private fun JSONArray.toStringList(): List<String> =
    (0 until length()).mapNotNull { index ->
        optString(index, "").trim().takeIf { it.isNotBlank() }
    }

private fun JSONArray.toBranchList(): List<BranchConfig> =
    (0 until length()).mapNotNull { index ->
        optJSONObject(index)?.toBranchConfig()
    }

private fun JSONObject.toBranchConfig(): BranchConfig? {
    val id = optString("id", "").trim()
    val label = optString("label", "").trim()
    val baseUrl = optString("baseUrl", "").trim()
    val apiKey = optString("apiKey", "").trim()
    val firebaseTopic = optString("firebaseTopic", BranchCatalog.DEFAULT_FIREBASE_TOPIC).trim()
    val theme = optString("theme", id).trim().ifBlank { id }
    val displayLabel = optString("displayLabel", label).trim().ifBlank { label }
    if (id.isBlank() || label.isBlank() || baseUrl.isBlank()) return null
    return BranchConfig(
        id = id,
        label = label,
        baseUrl = baseUrl,
        apiKey = apiKey,
        firebaseTopic = firebaseTopic.ifBlank { BranchCatalog.DEFAULT_FIREBASE_TOPIC },
        theme = theme,
        displayLabel = displayLabel
    )
}
