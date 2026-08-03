package com.inventario.app.data.bcv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class BcvRateFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {
    /**
     * Consulta ve.dolarapi.com y bcv.org.ve. Si coinciden usa cualquiera;
     * si difieren prioriza BCV oficial. Si BCV falla (SSL, etc.) usa dolarapi
     * sin propagar el error técnico al usuario.
     */
    suspend fun fetchUsdRate(): Result<Double> = withContext(Dispatchers.IO) {
        runCatching { resolveRate() }
    }

    private fun resolveRate(): Double {
        val dolarApiRate = fetchFromDolarApi()
        val bcvRate = fetchFromBcvSite()

        val raw = when {
            dolarApiRate != null && bcvRate != null ->
                if (ratesMatch(dolarApiRate, bcvRate)) dolarApiRate else bcvRate
            bcvRate != null -> bcvRate
            dolarApiRate != null -> dolarApiRate
            else -> error("No se pudo obtener la tasa BCV")
        }
        return roundRate(raw)
    }

    private fun roundRate(rate: Double): Double =
        kotlin.math.round(rate * 100) / 100.0

    private fun ratesMatch(a: Double, b: Double): Boolean =
        kotlin.math.abs(a - b) < 0.01

    private fun fetchFromDolarApi(): Double? = runCatching {
        val json = download(DOLAR_API_URL)
        val obj = JSONObject(json)
        val value = when {
            !obj.isNull("promedio") -> obj.getDouble("promedio")
            !obj.isNull("venta") -> obj.getDouble("venta")
            !obj.isNull("compra") -> obj.getDouble("compra")
            else -> null
        }
        value?.takeIf { it > 0 }
    }.getOrNull()

    private fun fetchFromBcvSite(): Double? = runCatching {
        val html = download(BCV_URL)
        parseUsdFromBcv(html)?.takeIf { it > 0 }
    }.getOrNull()

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
            if (!response.isSuccessful) {
                error("HTTP ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) error("Respuesta vacía")
            body
        }
    }

  /**
     * Extrae el USD del bloque oficial: `<div id="dolar">...<strong class="strong-tb">748,78640000</strong>`
     */
    fun parseUsdFromBcv(html: String): Double? {
        val patterns = listOf(
            Regex(
                """id\s*=\s*["']dolar["'][\s\S]{0,1200}?strong-tb[^>]*>\s*([0-9]{1,4}[.,][0-9]{2,10})\s*<""",
                setOf(RegexOption.IGNORE_CASE)
            ),
            Regex(
                """id\s*=\s*["']dolar["'][\s\S]{0,1200}?<strong[^>]*>\s*([0-9]{1,4}[.,][0-9]{2,10})\s*</strong>""",
                setOf(RegexOption.IGNORE_CASE)
            ),
            Regex(
                """<span>\s*USD\s*</span>[\s\S]{0,400}?<strong[^>]*>\s*([0-9]{1,4}[.,][0-9]{2,10})\s*</strong>""",
                setOf(RegexOption.IGNORE_CASE)
            )
        )

        for (pattern in patterns) {
            val match = pattern.find(html) ?: continue
            normalizeVeNumber(match.groupValues[1])?.let { rate ->
                if (rate > 0) return rate
            }
        }
        return null
    }

    /** Formato BCV Venezuela: 748,78640000 */
    private fun normalizeVeNumber(raw: String): Double? {
        val value = raw.trim()
        val normalized = when {
            value.contains(',') && value.contains('.') ->
                value.replace(".", "").replace(',', '.')
            value.contains(',') -> value.replace(',', '.')
            else -> value
        }
        return normalized.toDoubleOrNull()
    }

    companion object {
        private const val DOLAR_API_URL = "https://ve.dolarapi.com/v1/dolares/oficial"
        private const val BCV_URL = "https://www.bcv.org.ve/"
    }
}
