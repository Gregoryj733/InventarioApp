package com.inventario.app.data.sync

import android.content.Context

object CloudConfigStore {
    private const val PREFS = "cloud_sync_config"
    private const val KEY_BASE_URL = "sync_base_url"
    private const val KEY_API_KEY = "sync_api_key"
    private const val KEY_ACTIVE_BRANCH_ID = "active_branch_id"

    fun save(context: Context, config: SyncConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BASE_URL, config.baseUrl)
            .putString(KEY_API_KEY, config.apiKey)
            .apply()
        config.branchId?.let { saveActiveBranchId(context, it) }
    }

    fun saveActiveBranchId(context: Context, branchId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_BRANCH_ID, branchId)
            .apply()
    }

    fun loadActiveBranchId(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_BRANCH_ID, null)
            ?.takeIf { it.isNotBlank() }

    fun load(context: Context): SyncConfig? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val baseUrl = prefs.getString(KEY_BASE_URL, null).orEmpty()
        val apiKey = prefs.getString(KEY_API_KEY, null).orEmpty()
        if (baseUrl.isBlank()) return null
        val assetFallbacks = SyncConfig.loadAssetFallbacks(context)
        val branchId = loadActiveBranchId(context)
        return SyncConfig(
            baseUrl = baseUrl,
            apiKey = apiKey,
            fallbackUrls = assetFallbacks,
            branchId = branchId
        )
    }
}
