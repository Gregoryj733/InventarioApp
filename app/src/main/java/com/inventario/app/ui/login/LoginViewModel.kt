package com.inventario.app.ui.login

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.inventario.app.data.entity.UserRole
import com.inventario.app.data.repository.AuthRepository
import com.inventario.app.data.repository.LoginResult
import com.inventario.app.data.session.SessionManager
import com.inventario.app.data.sync.CloudConfigStore
import com.inventario.app.data.sync.CloudSyncInfo
import com.inventario.app.data.sync.CloudSyncStatus
import com.inventario.app.data.sync.SyncConfig
import com.inventario.app.data.sync.SyncServerResolver
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val username: String = "",
    val password: String = "",
    val loading: Boolean = false,
    val statusMessage: String? = null,
    val error: String? = null,
    val loggedInRole: UserRole? = null,
    val showServerConfig: Boolean = false,
    val serverUrl: String = "",
    val serverApiKey: String = "",
    val serverConfigMessage: String? = null,
    val activeServerUrl: String = ""
)

class LoginViewModel(
    private val authRepository: AuthRepository,
    private val sessionManager: SessionManager,
    private val appContext: Context,
    private val cloudSyncStatus: StateFlow<CloudSyncInfo>,
    private val restartCloudSync: () -> Unit
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()
    private var syncStatusJob: Job? = null

    init {
        if (sessionManager.isLoggedIn()) {
            _state.update { it.copy(loggedInRole = sessionManager.role()) }
        }
        refreshServerLabel()
    }

    fun onUsernameChange(value: String) = _state.update { it.copy(username = value, error = null) }
    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }

    fun toggleServerConfig() {
        _state.update { state ->
            val opening = !state.showServerConfig
            val current = if (opening) SyncConfig.load(appContext) else null
            state.copy(
                showServerConfig = opening,
                serverConfigMessage = null,
                serverUrl = if (opening) current?.baseUrl.orEmpty() else state.serverUrl,
                serverApiKey = if (opening) current?.apiKey.orEmpty() else state.serverApiKey
            )
        }
    }

    fun onServerUrlChange(value: String) =
        _state.update { it.copy(serverUrl = value, serverConfigMessage = null) }

    fun onServerApiKeyChange(value: String) =
        _state.update { it.copy(serverApiKey = value, serverConfigMessage = null) }

    fun saveServerConfig() {
        val url = _state.value.serverUrl.trim().trimEnd('/')
        val apiKey = _state.value.serverApiKey.trim()
        if (url.isEmpty()) {
            _state.update { it.copy(serverConfigMessage = "Ingresa la URL del servidor.") }
            return
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            _state.update {
                it.copy(serverConfigMessage = "La URL debe comenzar con http:// o https://")
            }
            return
        }
        val fallbacks = SyncConfig.load(appContext)?.fallbackUrls.orEmpty()
        applyServerConfig(SyncConfig(baseUrl = url, apiKey = apiKey, fallbackUrls = fallbacks))
        _state.update {
            it.copy(
                showServerConfig = false,
                serverConfigMessage = null,
                error = null,
                statusMessage = null
            )
        }
    }

    fun login() {
        val current = _state.value
        if (current.username.isBlank() || current.password.isBlank()) {
            _state.update { it.copy(error = "Ingresa usuario y contraseña.") }
            return
        }
        viewModelScope.launch {
            startSyncStatusWatch()
            _state.update {
                it.copy(
                    loading = true,
                    error = null,
                    statusMessage = "Conectando con el servidor…"
                )
            }
            when (val result = attemptLogin(current.username, current.password, triedFallback = false)) {
                LoginResult.InvalidCredentials -> {
                    stopSyncStatusWatch()
                    _state.update {
                        it.copy(loading = false, statusMessage = null, error = "Credenciales incorrectas.")
                    }
                }
                LoginResult.Inactive -> {
                    stopSyncStatusWatch()
                    _state.update {
                        it.copy(
                            loading = false,
                            statusMessage = null,
                            error = "Usuario desactivado. Contacta al administrador."
                        )
                    }
                }
                is LoginResult.Unavailable -> {
                    stopSyncStatusWatch()
                    _state.update {
                        it.copy(
                            loading = false,
                            statusMessage = null,
                            error = result.message,
                            showServerConfig = true
                        )
                    }
                }
                is LoginResult.Success -> {
                    stopSyncStatusWatch()
                    val user = result.user
                    sessionManager.saveSession(user.username, user.role, user.sucursal)
                    _state.update {
                        it.copy(
                            loading = false,
                            statusMessage = null,
                            loggedInRole = user.role
                        )
                    }
                }
            }
        }
    }

    private suspend fun attemptLogin(
        username: String,
        password: String,
        triedFallback: Boolean
    ): LoginResult {
        when (val result = authRepository.login(username, password)) {
            is LoginResult.Unavailable -> if (!triedFallback) {
                _state.update { it.copy(statusMessage = "Buscando servidor alternativo…") }
                val fallback = SyncServerResolver.findFallback(appContext) ?: return result
                applyServerConfig(fallback, clearError = true)
                startSyncStatusWatch()
                _state.update {
                    it.copy(statusMessage = "Reintentando en ${fallback.baseUrl}…")
                }
                return attemptLogin(username, password, triedFallback = true)
            } else {
                return result
            }
            else -> return result
        }
    }

    private fun applyServerConfig(config: SyncConfig, clearError: Boolean = false) {
        CloudConfigStore.save(appContext, config)
        restartCloudSync()
        refreshServerLabel()
        if (clearError) {
            _state.update { it.copy(error = null) }
        }
    }

    private fun refreshServerLabel() {
        val url = SyncConfig.load(appContext)?.baseUrl.orEmpty()
        _state.update {
            it.copy(
                activeServerUrl = url,
                serverUrl = url.ifBlank { it.serverUrl },
                serverApiKey = SyncConfig.load(appContext)?.apiKey.orEmpty().ifBlank { it.serverApiKey }
            )
        }
    }

    private fun startSyncStatusWatch() {
        syncStatusJob?.cancel()
        syncStatusJob = viewModelScope.launch {
            cloudSyncStatus.collect { info ->
                if (!_state.value.loading) return@collect
                val detail = info.detail?.takeIf { it.isNotBlank() } ?: return@collect
                if (info.status == CloudSyncStatus.SYNCING) {
                    _state.update { it.copy(statusMessage = detail) }
                }
            }
        }
    }

    private fun stopSyncStatusWatch() {
        syncStatusJob?.cancel()
        syncStatusJob = null
    }

    companion object {
        fun factory(
            authRepository: AuthRepository,
            sessionManager: SessionManager,
            appContext: Context,
            cloudSyncStatus: StateFlow<CloudSyncInfo>,
            restartCloudSync: () -> Unit
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return LoginViewModel(
                    authRepository,
                    sessionManager,
                    appContext.applicationContext,
                    cloudSyncStatus,
                    restartCloudSync
                ) as T
            }
        }
    }
}
