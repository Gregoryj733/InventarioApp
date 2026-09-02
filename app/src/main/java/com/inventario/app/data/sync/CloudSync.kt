package com.inventario.app.data.sync

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class CloudSyncStatus {
    IDLE,
    SYNCING,
    SYNCED,
    OFFLINE,
    ERROR
}

data class CloudSyncInfo(
    val status: CloudSyncStatus = CloudSyncStatus.IDLE,
    val detail: String? = null
)

/** Eventos push del servidor vía WebSocket: qué colección cambió. */
sealed class CloudEvent {
    data object Inventory : CloudEvent()
    data object Sales : CloudEvent()
    data object CashClosings : CloudEvent()
    data object Users : CloudEvent()
    data object DiscountTickets : CloudEvent()
}

class ApiException(val code: Int, message: String) : Exception(message)

/**
 * Mensaje amigable para mostrar en pantallas de acción (confirmar pedido,
 * guardar cierre, etc.), a diferencia de [CloudSync]'s `toSyncDetail()`
 * interno que alimenta el banner de estado de conexión.
 *
 * Prioriza el mensaje que ya manda el servidor en el cuerpo JSON (p. ej.
 * "Stock insuficiente para...", "No tienes permisos para esta acción"), que
 * ya es texto en español listo para el usuario. Solo cae a un mensaje
 * genérico cuando el servidor no devolvió cuerpo JSON (p. ej. un 404/502 con
 * una página HTML cruda, como ocurre si el servidor quedó desactualizado o
 * está "despertando" en el plan gratuito), evitando mostrar texto crudo como
 * "HTTP 404" al usuario.
 */
fun Throwable.toUserMessage(fallback: String = "Ocurrió un error inesperado."): String {
    if (this !is ApiException) {
        return when {
            isTransientNetworkFailure() ->
                "Sin conexión a internet o el servidor no responde. " +
                    "En plan gratuito puede tardar hasta 1 minuto en iniciar; inténtalo de nuevo."
            else -> localizedMessage ?: fallback
        }
    }
    val raw = message
    val hasServerMessage = raw != null && raw.isNotBlank() && !raw.matches(Regex("^HTTP \\d+$"))
    if (hasServerMessage) return raw
    return when (code) {
        0 -> "Sin configuración válida en sync_config.json ni en ajustes locales."
        404 -> "No se pudo contactar al servidor (HTTP 404). Es posible que el servidor de " +
            "sincronización esté desactualizado; inténtalo de nuevo en unos minutos."
        502, 503, 504 -> "El servidor de sincronización no responde (HTTP $code). En el plan " +
            "gratuito puede tardar hasta 1 minuto en iniciar: vuelve a intentarlo."
        in 500..599 -> "Error del servidor de sincronización (HTTP $code). Vuelve a intentarlo."
        else -> fallback
    }
}

/** Timeouts, DNS o conexión rechazada: suelen ocurrir mientras Render/Neon despiertan. */
fun Throwable.isTransientNetworkFailure(): Boolean {
    if (this is java.net.SocketTimeoutException ||
        this is java.net.ConnectException ||
        this is java.net.UnknownHostException ||
        this is java.net.NoRouteToHostException ||
        this is java.io.InterruptedIOException
    ) {
        return true
    }
    val msg = message.orEmpty()
    return msg.contains("timeout", ignoreCase = true) ||
        msg.contains("timed out", ignoreCase = true) ||
        msg.contains("failed to connect", ignoreCase = true) ||
        msg.contains("Unable to resolve host", ignoreCase = true) ||
        msg.contains("Connection reset", ignoreCase = true) ||
        msg.contains("Connection refused", ignoreCase = true) ||
        msg.contains("network", ignoreCase = true)
}

/**
 * Único punto de acceso a la nube: helpers REST (usados por los repositorios)
 * más un cliente WebSocket persistente que sustituye el polling anterior.
 * Todas las pantallas dependen exclusivamente de esta clase para leer o
 * escribir datos; no hay almacenamiento local salvo la caché en memoria que
 * cada repositorio mantiene actualizada con los eventos de este canal.
 */
class CloudSync(private val config: SyncConfig) {
    /** Identificador de sucursal asociado a esta instancia de sync (si aplica). */
    val branchId: String? get() = config.branchId

    private val _status = MutableStateFlow(CloudSyncInfo())
    val status: StateFlow<CloudSyncInfo> = _status.asStateFlow()

    private val _events = MutableSharedFlow<CloudEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<CloudEvent> = _events.asSharedFlow()

    /** Emite cuando el servidor rechaza el token de sesión (vencido o inválido). */
    private val _sessionExpired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val sessionExpired: SharedFlow<Unit> = _sessionExpired.asSharedFlow()

    private val client = OkHttpClient.Builder()
        // Render free puede tardar ~30–60s en el cold start; un connect corto
        // hace que el login falle antes de que el contenedor esté listo.
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    @Volatile private var authToken: String? = null
    @Volatile private var networkAvailable: Boolean = true
    @Volatile private var intentionalStop = false
    /** Evita cerrar sesión por 401 de peticiones en vuelo tras [stop]. */
    @Volatile private var acceptSessionExpirySignals = true
    private var reconnectAttempts = 0
    private var syncScope: CoroutineScope? = null
    private var reconnectJob: Job? = null
    private var webSocket: WebSocket? = null

    fun setAuthToken(token: String?) {
        authToken = token
    }

    fun start(scope: CoroutineScope) {
        syncScope = scope
        intentionalStop = false
        acceptSessionExpirySignals = true
        if (!config.isConfigured) {
            setStatus(CloudSyncStatus.ERROR, NOT_CONFIGURED_MESSAGE)
            return
        }
        scope.launch(Dispatchers.IO) {
            setStatus(CloudSyncStatus.SYNCING)
            runCatching { wakeServerWithRetry() }
                .onSuccess { connectWebSocket() }
                .onFailure { error ->
                    Log.w(TAG, "Sync startup failed", error)
                    setStatus(CloudSyncStatus.ERROR, error.toSyncDetail())
                    scheduleReconnect()
                }
        }
    }

    fun setNetworkAvailable(available: Boolean) {
        val wasAvailable = networkAvailable
        networkAvailable = available
        if (!available) {
            webSocket?.cancel()
            setStatus(CloudSyncStatus.OFFLINE, "Sin conexión a internet")
            return
        }
        if (!wasAvailable || _status.value.status == CloudSyncStatus.ERROR) {
            reconnectNow("Reconectando…")
        }
    }

    fun stop() {
        intentionalStop = true
        acceptSessionExpirySignals = false
        reconnectJob?.cancel()
        webSocket?.close(1000, "app_stop")
        webSocket = null
        syncScope = null
    }

    // ---------- Helpers REST usados por los repositorios ----------

    suspend fun get(path: String, query: Map<String, String> = emptyMap()): JSONObject =
        withContext(Dispatchers.IO) {
            requireConfigured()
            executeJson(Request.Builder().url(endpoint(path, query)).get().withAuth().build())
        }

    suspend fun postJson(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        requireConfigured()
        executeJson(
            Request.Builder()
                .url(endpoint(path))
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .withAuth()
                .build()
        )
    }

    suspend fun putJson(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        requireConfigured()
        executeJson(
            Request.Builder()
                .url(endpoint(path))
                .put(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .withAuth()
                .build()
        )
    }

    suspend fun patchJson(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        requireConfigured()
        executeJson(
            Request.Builder()
                .url(endpoint(path))
                .patch(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .withAuth()
                .build()
        )
    }

    suspend fun delete(path: String, query: Map<String, String> = emptyMap()): JSONObject =
        withContext(Dispatchers.IO) {
            requireConfigured()
            executeJson(Request.Builder().url(endpoint(path, query)).delete().withAuth().build())
        }

    suspend fun postMultipart(path: String, fileName: String, bytes: ByteArray): JSONObject =
        withContext(Dispatchers.IO) {
            requireConfigured()
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    fileName,
                    bytes.toRequestBody("application/octet-stream".toMediaType())
                )
                .build()
            executeJson(Request.Builder().url(endpoint(path)).post(body).withAuth().build())
        }

    // ---------- WebSocket ----------

    private fun connectWebSocket() {
        if (intentionalStop) return
        if (!networkAvailable) {
            setStatus(CloudSyncStatus.OFFLINE, "Sin conexión a internet")
            return
        }
        // La clave va como query param (la única forma que revisa el
        // upgrade "crudo" del WebSocket en el servidor), pero también se
        // agrega como header X-Api-Key por si la conexión termina llegando
        // como una request HTTP normal (p. ej. reintento tras un fallo de
        // negociación de protocolo) en vez de un upgrade real.
        val request = Request.Builder().url(wsEndpoint()).withAuth().build()
        webSocket = client.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            reconnectAttempts = 0
            setStatus(CloudSyncStatus.SYNCED)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val type = runCatching { JSONObject(text).optString("type") }.getOrNull() ?: return
            val event = when (type) {
                "inventory" -> CloudEvent.Inventory
                "sales" -> CloudEvent.Sales
                "cashClosings" -> CloudEvent.CashClosings
                "users" -> CloudEvent.Users
                "discountTickets" -> CloudEvent.DiscountTickets
                else -> null
            }
            event?.let { _events.tryEmit(it) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (intentionalStop) return
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (intentionalStop) return
            Log.w(TAG, "WebSocket failure", t)
            if (networkAvailable) {
                // Si el handshake devolvió un código HTTP (p. ej. 401 en vez
                // de 101), lo traducimos igual que un error REST para que el
                // usuario vea un mensaje claro en vez del texto crudo de
                // OkHttp ("Expected HTTP 101 response but was '401 ...'").
                val detail = response?.let { ApiException(it.code, it.message).toSyncDetail() }
                    ?: t.toSyncDetail()
                setStatus(CloudSyncStatus.ERROR, detail)
            }
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (intentionalStop || !networkAvailable) return
        reconnectJob?.cancel()
        val delayMs = RECONNECT_DELAYS_MS[reconnectAttempts.coerceAtMost(RECONNECT_DELAYS_MS.lastIndex)]
        reconnectAttempts++
        reconnectJob = syncScope?.launch(Dispatchers.IO) {
            delay(delayMs)
            if (intentionalStop || !networkAvailable) return@launch
            runCatching { wakeServerWithRetry(maxAttempts = 1) }
            connectWebSocket()
        }
    }

    private fun reconnectNow(detail: String) {
        setStatus(CloudSyncStatus.SYNCING, detail)
        reconnectJob?.cancel()
        reconnectJob = syncScope?.launch(Dispatchers.IO) {
            runCatching { wakeServerWithRetry() }
                .onSuccess { connectWebSocket() }
                .onFailure { error ->
                    setStatus(CloudSyncStatus.ERROR, error.toSyncDetail())
                    scheduleReconnect()
                }
        }
    }

    /**
     * Despierta el sync-server (plan free de Render/Neon) hasta que /health
     * responda OK. Usado al arrancar el WebSocket y antes del login.
     */
    suspend fun ensureServerReady(maxAttempts: Int = RETRY_DELAYS_MS.size) {
        requireConfigured()
        wakeServerWithRetry(maxAttempts)
    }

    /** Login: menos reintentos si el servidor local ya respondió al probe. */
    suspend fun ensureServerReadyForLogin() {
        requireConfigured()
        val attempts = if (SyncServerResolver.probe(config)) 1 else RETRY_DELAYS_MS.size
        wakeServerWithRetry(attempts)
    }

    /** Espera a que el servidor (posiblemente "dormido" en el plan free) responda /health. */
    private suspend fun wakeServerWithRetry(maxAttempts: Int = RETRY_DELAYS_MS.size) {
        var lastError: Throwable? = null
        for (attempt in 0 until maxAttempts) {
            if (attempt > 0) {
                setStatus(
                    CloudSyncStatus.SYNCING,
                    "Servidor iniciando… reintento ${attempt + 1}/$maxAttempts"
                )
                delay(RETRY_DELAYS_MS[attempt])
            }
            val result = runCatching {
                executeJson(Request.Builder().url(endpoint("/health")).get().withAuth().build())
            }
            if (result.isSuccess) return
            lastError = result.exceptionOrNull()
            if (lastError?.isRetryableSyncError() != true) throw lastError!!
        }
        throw lastError ?: ApiException(502, "HTTP 502")
    }

    // ---------- Internals ----------

    private fun requireConfigured() {
        if (!config.isConfigured) throw ApiException(0, NOT_CONFIGURED_MESSAGE)
    }

    /**
     * Solo fuerza cierre de sesión cuando el servidor rechaza explícitamente el
     * JWT (vencido o inválido). Un 401 "Sesión requerida" suele ser transitorio
     * (reinicio de sync, petición sin token aún adjunto) y no debe desloguear.
     */
    private fun notifySessionExpiredIfNeeded(serverMessage: String?) {
        if (!acceptSessionExpirySignals || intentionalStop) return
        val message = serverMessage ?: return
        val isJwtRejected = message.contains("inválida", ignoreCase = true) ||
            message.contains("invalida", ignoreCase = true) ||
            message.contains("expirada", ignoreCase = true)
        if (isJwtRejected) {
            _sessionExpired.tryEmit(Unit)
        }
    }

    private fun executeJson(request: Request): JSONObject {
        client.newCall(request).execute().use { response ->
            val bodyText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val serverMessage = runCatching {
                    JSONObject(bodyText).optString("error")
                }.getOrNull()?.takeIf { it.isNotBlank() }
                notifySessionExpiredIfNeeded(serverMessage)
                throw ApiException(response.code, serverMessage ?: "HTTP ${response.code}")
            }
            if (bodyText.isBlank()) return JSONObject()
            return JSONObject(bodyText)
        }
    }

    private fun endpoint(path: String, query: Map<String, String> = emptyMap()): String {
        val base = config.baseUrl.trimEnd('/')
        val builder = "$base$path".toHttpUrl().newBuilder()
        query.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build().toString()
    }

    private fun wsEndpoint(): String {
        val base = config.baseUrl.trimEnd('/')
        val wsBase = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            else -> base
        }
        val params = mutableListOf<String>()
        if (config.apiKey.isNotBlank()) {
            params.add("apiKey=${URLEncoder.encode(config.apiKey, StandardCharsets.UTF_8)}")
        }
        authToken?.takeIf { it.isNotBlank() }?.let { token ->
            params.add("token=${URLEncoder.encode(token, StandardCharsets.UTF_8)}")
        }
        return if (params.isEmpty()) "$wsBase/v1/ws" else "$wsBase/v1/ws?${params.joinToString("&")}"
    }

    private fun Request.Builder.withAuth(): Request.Builder {
        if (config.apiKey.isNotBlank()) {
            addHeader("X-Api-Key", config.apiKey)
        }
        authToken?.let { addHeader("Authorization", "Bearer $it") }
        return this
    }

    private fun setStatus(status: CloudSyncStatus, detail: String? = null) {
        _status.value = CloudSyncInfo(status = status, detail = detail)
    }

    private fun Throwable.toSyncDetail(): String = when (this) {
        is ApiException -> when (code) {
            0 -> message ?: NOT_CONFIGURED_MESSAGE
            401 -> "Clave API inválida. Revisa la configuración de sincronización."
            403 -> "Acceso denegado al servidor de sincronización."
            404 -> "Servidor no encontrado. Verifica la URL del servidor."
            502, 503, 504 ->
                "El servidor de sincronización no responde (HTTP $code). " +
                    "En plan gratuito puede tardar hasta 1 minuto en iniciar; la app reintentará sola."
            in 500..599 -> "Error del servidor de sincronización (HTTP $code)."
            else -> message ?: "Error HTTP $code"
        }
        else -> when {
            isTransientNetworkFailure() ->
                "Sin conexión a internet o servidor inaccesible."
            else -> localizedMessage ?: "Error de sincronización"
        }
    }

    private fun Throwable.isRetryableSyncError(): Boolean =
        (this is ApiException && code in RETRYABLE_HTTP_CODES) || isTransientNetworkFailure()

    companion object {
        private const val TAG = "CloudSync"
        private val RETRYABLE_HTTP_CODES = setOf(502, 503, 504)
        // ~0+8+15+25+40+45s de espera entre intentos; con connectTimeout 45s
        // cubre cold starts largos de Render sin marcar "credenciales incorrectas".
        private val RETRY_DELAYS_MS = longArrayOf(0L, 8_000L, 15_000L, 25_000L, 40_000L, 45_000L)
        private val RECONNECT_DELAYS_MS = longArrayOf(3_000L, 6_000L, 12_000L, 20_000L, 30_000L)
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val NOT_CONFIGURED_MESSAGE =
            "Sin configuración válida en sync_config.json ni en ajustes locales."

        fun newSyncId(): String = UUID.randomUUID().toString()
    }
}
