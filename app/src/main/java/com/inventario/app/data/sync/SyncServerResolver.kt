package com.inventario.app.data.sync

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Busca un sync-server alcanzable cuando la URL principal (p. ej. Render) no
 * responde. Prueba URLs de respaldo definidas en sync_config.json.
 */
object SyncServerResolver {

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.SECONDS)
        .build()

    /** Emulador Android → máquina host donde corre `node server.js`. */
    private const val EMULATOR_HOST = "http://10.0.2.2:8787"

    suspend fun findFallback(context: Context): SyncConfig? = withContext(Dispatchers.IO) {
        val base = SyncConfig.load(context) ?: return@withContext null
        if (probe(base)) return@withContext null

        val candidates = buildList {
            addAll(base.fallbackUrls)
            add(EMULATOR_HOST)
        }
            .map { it.trim().trimEnd('/') }
            .filter { it.isNotBlank() && it != base.baseUrl.trimEnd('/') }
            .distinct()

        candidates.firstOrNull { url ->
            probe(SyncConfig(baseUrl = url, apiKey = base.apiKey))
        }?.let { url ->
            SyncConfig(baseUrl = url, apiKey = base.apiKey, fallbackUrls = base.fallbackUrls)
        }
    }

    fun probe(config: SyncConfig): Boolean {
        if (!config.isConfigured) return false
        val base = config.baseUrl.trimEnd('/')
        val request = Request.Builder()
            .url("$base/health")
            .get()
            .apply {
                if (config.apiKey.isNotBlank()) {
                    addHeader("X-Api-Key", config.apiKey)
                }
            }
            .build()
        return runCatching {
            probeClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        }.getOrDefault(false)
    }
}
