package com.inventario.app.data.sync

import android.content.Context
import org.json.JSONObject

data class SyncConfig(
    val baseUrl: String,
    val apiKey: String
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() &&
            !baseUrl.contains("REEMPLAZA", ignoreCase = true) &&
            !baseUrl.contains("TU_SERVIDOR", ignoreCase = true) &&
            (baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))

    companion object {
        fun load(context: Context): SyncConfig? {
            CloudConfigStore.load(context)?.let { stored ->
                if (stored.isConfigured) return stored
            }
            return loadFromAssets(context)
        }

        private fun loadFromAssets(context: Context): SyncConfig? = runCatching {
            context.assets.open("sync_config.json").bufferedReader().use { reader ->
                val json = JSONObject(reader.readText())
                SyncConfig(
                    baseUrl = json.optString("baseUrl", "").trim(),
                    apiKey = json.optString("apiKey", "").trim()
                )
            }
        }.getOrNull()?.takeIf { it.isConfigured }
    }
}
