package com.inventario.app.data.sync

import android.content.Context
import com.inventario.app.data.branch.BranchCatalog
import org.json.JSONArray
import org.json.JSONObject

data class SyncConfig(
    val baseUrl: String,
    val apiKey: String,
    val fallbackUrls: List<String> = emptyList(),
    val branchId: String? = null
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() &&
            !baseUrl.contains("REEMPLAZA", ignoreCase = true) &&
            !baseUrl.contains("TU_SERVIDOR", ignoreCase = true) &&
            (baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))

    companion object {
        fun load(context: Context): SyncConfig? {
            val catalog = BranchCatalog(context)
            val activeBranchId = CloudConfigStore.loadActiveBranchId(context)
            val branch = activeBranchId?.let(catalog::findById)
                ?: catalog.defaultBranch
            if (branch != null && branch.isConfigured) {
                return branch.toSyncConfig(catalog.fallbackUrls).copy(branchId = branch.id)
            }
            CloudConfigStore.load(context)?.let { stored ->
                if (stored.isConfigured) return stored
            }
            return loadLegacyFromAssets(context)
        }

        fun loadForBranch(context: Context, branchId: String): SyncConfig? {
            val catalog = BranchCatalog(context)
            return catalog.findById(branchId)
                ?.toSyncConfig(catalog.fallbackUrls)
                ?.copy(branchId = branchId)
                ?.takeIf { it.isConfigured }
        }

        fun loadAssetFallbacks(context: Context): List<String> =
            BranchCatalog(context).fallbackUrls

        private fun loadLegacyFromAssets(context: Context): SyncConfig? = runCatching {
            context.assets.open(BranchCatalog.ASSET_NAME).bufferedReader().use { reader ->
                val json = JSONObject(reader.readText())
                SyncConfig(
                    baseUrl = json.optString("baseUrl", "").trim(),
                    apiKey = json.optString("apiKey", "").trim(),
                    fallbackUrls = json.optJSONArray("fallbackUrls")?.toStringList().orEmpty()
                )
            }
        }.getOrNull()?.takeIf { it.isConfigured }

        private fun JSONArray.toStringList(): List<String> =
            (0 until length()).mapNotNull { index ->
                optString(index, "").trim().takeIf { it.isNotBlank() }
            }
    }
}
