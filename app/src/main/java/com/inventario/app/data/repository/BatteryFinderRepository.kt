package com.inventario.app.data.repository

import android.content.Context
import com.inventario.app.data.entity.BatteryFinderEntry
import com.inventario.app.data.sync.CloudSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Catálogo de compatibilidad marca/modelo/año -> batería del módulo "Validar
 * Batería". Intenta leerlo desde la nube (`/v1/battery-finder`); si la
 * respuesta está vacía, incompleta o sospechosa (p. ej. todas las filas con
 * la misma batería), usa la copia embebida en assets.
 */
class BatteryFinderRepository(
    private val context: Context,
    private var cloudSync: CloudSync
) {
    private var cache: List<BatteryFinderEntry>? = null

    fun setCloudSync(sync: CloudSync) {
        cloudSync = sync
        cache = null
    }

    suspend fun fetchAll(): List<BatteryFinderEntry> = withContext(Dispatchers.IO) {
        cache?.let { return@withContext it }

        val fromCloud = runCatching {
            cloudSync.get("/v1/battery-finder").optJSONArray("items")?.toBatteryFinderList()
        }.getOrNull()

        val fromAssets = loadFromAssets()
        val entries = selectCatalog(fromCloud, fromAssets)

        if (entries.isNotEmpty() && isUsableCatalog(entries)) {
            cache = entries
        }
        entries
    }

    private fun selectCatalog(
        cloud: List<BatteryFinderEntry>?,
        assets: List<BatteryFinderEntry>
    ): List<BatteryFinderEntry> {
        val cloudList = cloud.orEmpty()
        return when {
            isUsableCatalog(assets) && !isUsableCatalog(cloudList) -> assets
            isUsableCatalog(cloudList) && !isUsableCatalog(assets) -> cloudList
            isUsableCatalog(assets) && isUsableCatalog(cloudList) -> {
                // Preferir el catálogo con más combinaciones distintas.
                if (cloudList.size >= assets.size) cloudList else assets
            }
            assets.isNotEmpty() -> assets
            else -> cloudList
        }
    }

  /** Un catálogo válido debe cubrir varias marcas y varios códigos de batería. */
    private fun isUsableCatalog(entries: List<BatteryFinderEntry>): Boolean {
        if (entries.size < 100) return false
        val uniqueMarcas = entries.map { it.marca }.distinct().size
        val uniqueBaterias = entries.map { it.bateria }.distinct().size
        return uniqueMarcas >= 20 && uniqueBaterias >= 10
    }

    private fun loadFromAssets(): List<BatteryFinderEntry> = runCatching {
        context.assets.open("battery-finder.json").bufferedReader().use { reader ->
            JSONArray(reader.readText().trimStart('\uFEFF')).toBatteryFinderList()
        }
    }.getOrDefault(emptyList())
}
