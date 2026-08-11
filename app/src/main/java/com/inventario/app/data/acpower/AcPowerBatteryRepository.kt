package com.inventario.app.data.acpower

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Catálogo en vivo de [acpowervzla.com](https://acpowervzla.com/#encuentra-tu-bateria):
 * marcas embebidas, modelos vía plugin YMM y productos vía WooCommerce Store API /
 * búsqueda YMM del sitio.
 */
class AcPowerBatteryRepository(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {
    private var marcasCache: List<String>? = null
    private val modelosCache = mutableMapOf<String, List<String>>()

    suspend fun fetchMarcas(): List<String> = withContext(Dispatchers.IO) {
        marcasCache?.let { return@withContext it }
        val loaded = runCatching {
            context.assets.open("ac-power-marcas.json").bufferedReader().use { reader ->
                JSONArray(reader.readText().trimStart('\uFEFF')).toStringList()
            }
        }.getOrDefault(emptyList())
        if (loaded.isNotEmpty()) {
            marcasCache = loaded
        }
        loaded
    }

    suspend fun fetchModelos(marca: String): List<String> = withContext(Dispatchers.IO) {
        val key = marca.trim()
        modelosCache[key]?.let { return@withContext it }
        val encodedMarca = URLEncoder.encode(key, Charsets.UTF_8.name())
        val url = "$YMM_AJAX_URL?action=ymm_selector_fetch&cId=0&values[]=$encodedMarca"
        val body = download(url)
        val modelos = JSONArray(body).toStringList().sorted()
        if (modelos.isNotEmpty()) {
            modelosCache[key] = modelos
        }
        modelos
    }

    suspend fun searchByVehicle(marca: String, modelo: String): List<AcPowerBatteryProduct> =
        withContext(Dispatchers.IO) {
            val url = buildVehicleSearchUrl(marca, modelo)
            val html = download(url)
            parseProductsFromHtml(html)
        }

    suspend fun searchByCode(code: String): List<AcPowerBatteryProduct> = withContext(Dispatchers.IO) {
        val query = code.trim()
        if (query.length < 2) return@withContext emptyList()
        val url = "$STORE_API_URL/products?search=${URLEncoder.encode(query, Charsets.UTF_8.name())}"
        val body = download(url)
        parseProductsFromStoreApi(body)
    }

    private fun buildVehicleSearchUrl(marca: String, modelo: String): String {
        return "https://acpowervzla.com/".toHttpUrl().newBuilder()
            .addQueryParameter("ymm_search", "1")
            .addQueryParameter("post_type", "product")
            .addQueryParameter("_make", marca.trim())
            .addQueryParameter("_model", modelo.trim())
            .build()
            .toString()
    }

    private fun parseProductsFromHtml(html: String): List<AcPowerBatteryProduct> {
        val titleRegex = Regex(
            """<h3\s+class="wd-entities-title">\s*<a[^>]*>([^<]+)</a>""",
            RegexOption.IGNORE_CASE
        )
        return titleRegex.findAll(html)
            .map { match -> productFromTitle(match.groupValues[1].decodeHtmlEntities()) }
            .distinctBy { it.code }
            .toList()
    }

    private fun parseProductsFromStoreApi(json: String): List<AcPowerBatteryProduct> {
        val array = JSONArray(json)
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val name = item.optString("name").decodeHtmlEntities()
                if (name.isBlank()) continue
                val code = extractBatteryCode(name) ?: continue
                val line = item.optJSONArray("categories")
                    ?.optJSONObject(0)
                    ?.optString("name")
                    ?.decodeHtmlEntities()
                    ?.takeIf { it.isNotBlank() }
                val imageUrl = item.optJSONArray("images")
                    ?.optJSONObject(0)
                    ?.optString("src")
                    ?.takeIf { it.isNotBlank() }
                val features = item.optString("short_description")
                    .decodeHtmlEntities()
                    .parseListItems()
                add(
                    AcPowerBatteryProduct(
                        name = name,
                        code = code,
                        line = line,
                        imageUrl = imageUrl,
                        features = features
                    )
                )
            }
        }
    }

    private fun productFromTitle(title: String): AcPowerBatteryProduct {
        val code = extractBatteryCode(title).orEmpty()
        return AcPowerBatteryProduct(
            name = title,
            code = code,
            line = inferLineFromName(title),
            imageUrl = null,
            features = emptyList()
        )
    }

    private fun extractBatteryCode(name: String): String? =
        BATTERY_CODE_REGEX.find(name)?.groupValues?.get(1)?.uppercase()

    private fun inferLineFromName(name: String): String? = when {
        name.contains("CARGA PESADA", ignoreCase = true) -> "Línea AC POWER MAXX Carga Pesada"
        name.contains("AGRÍCOLA", ignoreCase = true) || name.contains("AGRICOLA", ignoreCase = true) ->
            "Línea AC POWER Agrícola"
        name.contains("MAXX", ignoreCase = true) -> "Línea AC POWER MAXX"
        else -> null
    }

    private fun String.parseListItems(): List<String> =
        Regex("""<li[^>]*>([\s\S]*?)</li>""", RegexOption.IGNORE_CASE)
            .findAll(this)
            .map { it.groupValues[1].replace(Regex("<[^>]+>"), "").trim() }
            .filter { it.isNotBlank() }
            .toList()

    private fun String.decodeHtmlEntities(): String = replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#8211;", "–")
        .replace("&#8217;", "'")
        .replace("&aacute;", "á")
        .replace("&eacute;", "é")
        .replace("&iacute;", "í")
        .replace("&oacute;", "ó")
        .replace("&uacute;", "ú")
        .replace("&ntilde;", "ñ")
        .replace("&Aacute;", "Á")
        .replace("&Eacute;", "É")
        .replace("&Iacute;", "Í")
        .replace("&Oacute;", "Ó")
        .replace("&Uacute;", "Ú")
        .replace("&Ntilde;", "Ñ")
        .trim()

    private fun download(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            )
            .header("Accept", "application/json,text/html,application/xhtml+xml,*/*;q=0.8")
            .header("Accept-Language", "es-VE,es;q=0.9,en;q=0.8")
            .get()
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            response.body?.string().orEmpty().also { body ->
                if (body.isBlank()) error("Respuesta vacía")
            }
        }
    }

    private fun JSONArray.toStringList(): List<String> = buildList {
        for (i in 0 until length()) {
            val value = optString(i).trim()
            if (value.isNotBlank()) add(value)
        }
    }

    companion object {
        private const val YMM_AJAX_URL =
            "https://acpowervzla.com/wp-content/plugins/ymm-search/ymm_ajax.php"
        private const val STORE_API_URL = "https://acpowervzla.com/wp-json/wc/store/v1"
        private val BATTERY_CODE_REGEX =
            Regex("""Modelo\s+([A-Za-z0-9]+-\d+)""", RegexOption.IGNORE_CASE)
    }
}
