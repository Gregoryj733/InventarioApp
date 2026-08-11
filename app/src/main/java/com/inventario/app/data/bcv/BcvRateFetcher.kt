package com.inventario.app.data.bcv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Obtiene la tasa USD/VES publicada por el Banco Central de Venezuela.
 *
 * **Regla de prioridad (obligatoria):** siempre se usa primero la tasa leída de
 * [https://www.bcv.org.ve/](https://www.bcv.org.ve/). Las demás fuentes (DolarFlow,
 * ve.dolarapi.com) solo se consultan cuando el sitio oficial no responde o no se
 * puede extraer el valor del HTML.
 */
class BcvRateFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {
    suspend fun fetchUsdRate(): Result<Double> = withContext(Dispatchers.IO) {
        runCatching { resolveRate() }
    }

  /** Prioridad: 1) bcv.org.ve oficial → 2) respaldos solo si (1) falla. */
    private fun resolveRate(): Double {
        fetchFromBcvSite()?.let { return roundRate(it) }

        fetchFromDolarFlow()?.let { return roundRate(it) }
        fetchFromDolarApi()?.let { return roundRate(it) }

        error("No se pudo obtener la tasa BCV")
    }

    private fun roundRate(rate: Double): Double =
        kotlin.math.round(rate * 100) / 100.0

    private fun fetchFromDolarFlow(): Double? = runCatching {
        val obj = JSONObject(download(DOLARFLOW_URL))
        if (!obj.optBoolean("exito", false)) return@runCatching null
        listOf("precio", "promedio")
            .firstNotNullOfOrNull { key ->
                if (!obj.isNull(key)) obj.getDouble(key) else null
            }
            ?.takeIf { it > 0 }
    }.getOrNull()

    private fun fetchFromDolarApi(): Double? = runCatching {
        val obj = JSONObject(download(DOLAR_API_URL))
        val value = when {
            !obj.isNull("promedio") -> obj.getDouble("promedio")
            !obj.isNull("venta") -> obj.getDouble("venta")
            !obj.isNull("compra") -> obj.getDouble("compra")
            else -> null
        }
        value?.takeIf { it > 0 }
    }.getOrNull()

    private fun fetchFromBcvSite(): Double? {
        repeat(BCV_FETCH_ATTEMPTS) { attempt ->
            val rate = runCatching {
                val html = download(BCV_URL)
                parseUsdFromBcv(html)?.takeIf { it > 0 }
            }.getOrNull()
            if (rate != null) return rate
            if (attempt < BCV_FETCH_ATTEMPTS - 1) {
                Thread.sleep(BCV_RETRY_DELAY_MS)
            }
        }
        return null
    }

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
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
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
        private const val BCV_URL = "https://www.bcv.org.ve/"
        private const val DOLARFLOW_URL = "https://dolarflow.com/api/oficial/"
        private const val DOLAR_API_URL = "https://ve.dolarapi.com/v1/dolares/oficial"
        private const val BCV_FETCH_ATTEMPTS = 2
        private const val BCV_RETRY_DELAY_MS = 800L

        private val CARACAS_ZONE = ZoneId.of("America/Caracas")

        /** true si la tasa guardada es de un día anterior (hora de Caracas). */
        fun isStale(fetchedAt: Long?): Boolean {
            if (fetchedAt == null) return true
            val fetchedDay = Instant.ofEpochMilli(fetchedAt).atZone(CARACAS_ZONE).toLocalDate()
            val today = LocalDate.now(CARACAS_ZONE)
            return fetchedDay.isBefore(today)
        }
    }
}
