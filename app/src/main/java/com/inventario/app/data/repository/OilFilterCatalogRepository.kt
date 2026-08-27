package com.inventario.app.data.repository

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import com.inventario.app.data.entity.OilFilterEntry
import com.inventario.app.data.oilfilter.OilFilterCatalogDatabase
import com.inventario.app.data.search.ProductSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/**
 * Catálogo local de filtro de aceite. La SQLite se siembra una vez desde
 * assets; las búsquedas corren en memoria para responder al tipear.
 */
class OilFilterCatalogRepository(
    private val context: Context
) {
    private val dbHelper = OilFilterCatalogDatabase(context)
    private val seedMutex = Mutex()

    @Volatile
    private var cache: List<IndexedCatalogEntry>? = null

    suspend fun ensureReady(): Int = withContext(Dispatchers.IO) {
        seedMutex.withLock {
            ensureCacheLocked()
            cache?.size ?: 0
        }
    }

    suspend fun search(query: String): List<OilFilterEntry> = withContext(Dispatchers.Default) {
        val indexed = seedMutex.withLock {
            ensureCacheLocked()
            cache.orEmpty()
        }
        val tokens = ProductSearch.tokenize(query)
        if (tokens.isEmpty()) return@withContext emptyList()
        val primary = tokens.first()
        indexed.asSequence()
            .filter { entry -> tokens.all { token -> entry.searchText.contains(token) } }
            .sortedWith(indexedComparator(primary))
            .take(MAX_RESULTS)
            .map { it.entry }
            .toList()
    }

    private fun ensureCacheLocked() {
        if (cache != null) return
        val rows = dbHelper.rowCount()
        val version = dbHelper.catalogVersion()
        if (rows == 0 || version != CATALOG_VERSION) {
            reseedFromAsset()
        }
        cache = readAllIndexed()
    }

    private fun reseedFromAsset() {
        val json = loadCatalogJson() ?: return
        val items = json.optJSONArray("items") ?: JSONArray()
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            db.delete(OilFilterCatalogDatabase.TABLE, null, null)
            db.delete("meta", null, null)
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val values = item.toSeedValues() ?: continue
                db.insert(OilFilterCatalogDatabase.TABLE, null, values)
            }
            db.insert(
                "meta",
                null,
                ContentValues().apply {
                    put("key", OilFilterCatalogDatabase.META_CATALOG_VERSION)
                    put("value", CATALOG_VERSION.toString())
                }
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun readAllIndexed(): List<IndexedCatalogEntry> {
        dbHelper.readableDatabase.query(
            OilFilterCatalogDatabase.TABLE,
            null,
            null,
            null,
            null,
            null,
            null
        ).use { cursor ->
            val entries = ArrayList<IndexedCatalogEntry>(cursor.count)
            while (cursor.moveToNext()) {
                val entry = cursor.toEntry()
                entries.add(
                    IndexedCatalogEntry(
                        entry = entry,
                        searchText = cursor.getString(cursor.getColumnIndexOrThrow("search_text")),
                        modeloNorm = normalizeSearch(entry.modelo),
                        marcaNorm = normalizeSearch(entry.marca)
                    )
                )
            }
            return entries
        }
    }

    private fun loadCatalogJson(): JSONObject? = runCatching {
        context.assets.open(ASSET_NAME).bufferedReader().use { reader ->
            JSONObject(reader.readText().trimStart('\uFEFF'))
        }
    }.getOrNull()

    private fun JSONObject.toSeedValues(): ContentValues? {
        val marca = optString("marca").trim()
        val modelo = optString("modelo").trim()
        val filtro = optString("filtroCodigo").trim()
        if (marca.isBlank() || modelo.isBlank() || filtro.isBlank()) return null
        val motor = optString("motor").trim()
        val cilindrada = optString("cilindrada").trim()
        val anio = optString("anio").trim()
        val categoria = optString("categoria").trim()
        val filtroRol = optString("filtroRol").trim().ifBlank { "primario" }
        val tipoFiltro = optString("tipoFiltro").trim()
        val aceites = optJSONArray("aceitesRecomendados").toJoined()
        val alternativas = optJSONArray("alternativas").toJoined()
        val equivalencias = optJSONArray("equivalencias").toJoined()
        val observaciones = optString("observaciones").trim()
        val searchText = normalizeSearch(
            listOf(marca, modelo, motor, cilindrada, anio, categoria, filtro, tipoFiltro, observaciones)
                .joinToString(" ")
        )
        return ContentValues().apply {
            put("marca", marca)
            put("modelo", modelo)
            put("motor", motor)
            put("cilindrada", cilindrada)
            put("anio", anio)
            put("categoria", categoria)
            put("filtro_codigo", filtro)
            put("filtro_rol", filtroRol)
            put("tipo_filtro", tipoFiltro)
            put("aceites", aceites)
            put("alternativas", alternativas)
            put("equivalencias", equivalencias)
            put("observaciones", observaciones)
            put("search_text", searchText)
        }
    }

    private fun Cursor.toEntry(): OilFilterEntry = OilFilterEntry(
        id = getLong(getColumnIndexOrThrow("id")),
        marca = getString(getColumnIndexOrThrow("marca")),
        modelo = getString(getColumnIndexOrThrow("modelo")),
        motor = getString(getColumnIndexOrThrow("motor")),
        cilindrada = getString(getColumnIndexOrThrow("cilindrada")),
        anio = getString(getColumnIndexOrThrow("anio")),
        categoria = getString(getColumnIndexOrThrow("categoria")),
        filtroCodigo = getString(getColumnIndexOrThrow("filtro_codigo")),
        filtroRol = getString(getColumnIndexOrThrow("filtro_rol")),
        tipoFiltro = getString(getColumnIndexOrThrow("tipo_filtro")),
        aceitesRecomendados = getString(getColumnIndexOrThrow("aceites")).splitJoined(),
        alternativas = getString(getColumnIndexOrThrow("alternativas")).splitJoined(),
        equivalencias = getString(getColumnIndexOrThrow("equivalencias")).splitJoined(),
        observaciones = getString(getColumnIndexOrThrow("observaciones"))
    )

    private data class IndexedCatalogEntry(
        val entry: OilFilterEntry,
        val searchText: String,
        val modeloNorm: String,
        val marcaNorm: String
    )

    companion object {
        private const val ASSET_NAME = "oil-filter-catalog.json"
        private const val CATALOG_VERSION = 1
        private const val MAX_RESULTS = 60
        private const val JOIN_SEP = " | "
        private val DIACRITICS = Regex("\\p{Mn}+")

        private fun indexedComparator(primary: String): Comparator<IndexedCatalogEntry> =
            compareBy<IndexedCatalogEntry> { entry ->
                when {
                    entry.modeloNorm.startsWith(primary) -> 0
                    entry.modeloNorm.contains(primary) -> 1
                    entry.marcaNorm.startsWith(primary) -> 2
                    else -> 3
                }
            }.thenBy { it.marcaNorm }.thenBy { it.modeloNorm }

        private fun JSONArray?.toJoined(): String {
            if (this == null) return ""
            val values = mutableListOf<String>()
            for (i in 0 until length()) {
                val value = optString(i).trim()
                if (value.isNotBlank()) values.add(value)
            }
            return values.joinToString(JOIN_SEP)
        }

        private fun String.splitJoined(): List<String> =
            split(JOIN_SEP).map { it.trim() }.filter { it.isNotBlank() }

        private fun normalizeSearch(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFD)
                .replace(DIACRITICS, "")
                .lowercase()
    }
}
