package com.inventario.app.ui.login

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.inventario.app.data.branch.BranchConfig
import com.inventario.app.data.branch.BranchManager
import com.inventario.app.data.entity.UserRole
import com.inventario.app.data.entity.canSwitchBranch
import com.inventario.app.data.entity.isBranchRestricted
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
    val activeServerUrl: String = "",
    val branches: List<BranchConfig> = emptyList(),
    val selectedBranchId: String = "",
    val bootstrapping: Boolean = true
)

class LoginViewModel(
    private val authRepository: AuthRepository,
    private val sessionManager: SessionManager,
    private val branchManager: BranchManager,
    private val appContext: Context,
    private val cloudSyncStatus: StateFlow<CloudSyncInfo>,
    private val restartCloudSync: (String?) -> Unit
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()
    private var syncStatusJob: Job? = null

    init {
        val role = sessionManager.role()
        val sucursal = sessionManager.sucursal()
        val branches = branchManager.branchesVisibleToUser(role, sucursal)
        val defaultBranchId = when {
            sessionManager.isLoggedIn() && role?.isBranchRestricted() == true ->
                branches.firstOrNull()?.id
                    ?: branchManager.getActiveBranch()?.id
            sessionManager.isLoggedIn() ->
                branchManager.getActiveBranch()?.id ?: branches.firstOrNull()?.id
            else -> branches.firstOrNull()?.id
        }.orEmpty()
        _state.update {
            it.copy(
                branches = branches,
                selectedBranchId = defaultBranchId,
                loggedInRole = if (sessionManager.isLoggedIn()) role else null
            )
        }
        viewModelScope.launch {
            if (defaultBranchId.isNotBlank()) {
                prepareBranchConnection(defaultBranchId)
            }
            refreshServerLabel()
            _state.update { it.copy(bootstrapping = false) }
        }
    }

    fun onUsernameChange(value: String) = _state.update { it.copy(username = value, error = null) }
    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }

    fun onBranchSelected(branchId: String) {
        if (branchId == _state.value.selectedBranchId) return
        _state.update { it.copy(selectedBranchId = branchId, error = null) }
        viewModelScope.launch {
            prepareBranchConnection(branchId)
            refreshServerLabel()
        }
    }

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
        val fallbacks = SyncConfig.loadAssetFallbacks(appContext)
        val branchId = _state.value.selectedBranchId.takeIf { it.isNotBlank() }
        applyServerConfig(
            SyncConfig(baseUrl = url, apiKey = apiKey, fallbackUrls = fallbacks, branchId = branchId)
        )
        _state.update {
            it.copy(
                showServerConfig = false,
                serverConfigMessage = null,
                error = null,
                statusMessage = null
            )
        }
        login()
    }

    fun login() {
        val current = _state.value
        if (current.selectedBranchId.isBlank()) {
            _state.update { it.copy(error = "Selecciona una sucursal.") }
            return
        }
        if (current.username.isBlank() || current.password.isBlank()) {
            _state.update { it.copy(error = "Ingresa usuario y contraseña.") }
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = true,
                    error = null,
                    statusMessage = "Preparando conexión…"
                )
            }
            startSyncStatusWatch()
            prepareBranchConnection(current.selectedBranchId)
            _state.update { it.copy(statusMessage = "Conectando con el servidor…") }
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
                    val branch = branchManager.allBranches()
                        .firstOrNull { it.id == current.selectedBranchId }
                    if (branch == null) {
                        sessionManager.clearTokenForBranch(current.selectedBranchId)
                        _state.update {
                            it.copy(
                                loading = false,
                                statusMessage = null,
                                error = "Sucursal no configurada. Contacta al administrador."
                            )
                        }
                        return@launch
                    }
                    if (!branchManager.canLoginToBranch(user.role, user.sucursal, branch)) {
                        val userBranch = branchManager.branchForSucursal(user.sucursal)
                        sessionManager.clearTokenForBranch(current.selectedBranchId)
                        val visibleBranches = userBranch?.let { listOf(it) } ?: branchManager.allBranches()
                        _state.update {
                            it.copy(
                                loading = false,
                                statusMessage = null,
                                branches = visibleBranches,
                                selectedBranchId = userBranch?.id ?: it.selectedBranchId,
                                error = if (userBranch != null) {
                                    "Tu usuario pertenece a ${userBranch.chipLabel}. " +
                                        "Selecciónala en el inicio de sesión."
                                } else {
                                    "No tienes acceso a ${branch.chipLabel}. " +
                                        "Verifica la sucursal asignada a tu usuario."
                                }
                            )
                        }
                        return@launch
                    }
                    val effectiveSucursal = branchManager.effectiveSucursalForLogin(user.sucursal, branch)
                    branchManager.saveBranchSession(current.selectedBranchId, result.token)
                    sessionManager.saveSession(user.username, user.role, effectiveSucursal)
                    if (user.role.isBranchRestricted()) {
                        branchManager.enforceBranchIsolationForBranch(user.role, branch.id)
                            ?: run {
                                sessionManager.clear()
                                _state.update {
                                    it.copy(
                                        loading = false,
                                        statusMessage = null,
                                        error = "No se pudo activar la sucursal. Inténtalo de nuevo."
                                    )
                                }
                                return@launch
                            }
                    }
                    // Reconectar con el JWT ya emitido: el restart previo al login
                    // ocurrió sin token y dejaba pedidos del día vacíos en Admin/Supervisor.
                    restartCloudSync(result.token)
                    _state.update {
                        it.copy(
                            loading = false,
                            statusMessage = null,
                            loggedInRole = user.role,
                            branches = branchManager.branchesVisibleToUser(user.role, effectiveSucursal),
                            selectedBranchId = current.selectedBranchId
                        )
                    }
                    if (user.role.canSwitchBranch()) {
                        val username = current.username
                        val password = current.password
                        val primaryBranchId = current.selectedBranchId
                        viewModelScope.launch {
                            prefetchOtherBranchTokens(username, password, primaryBranchId)
                        }
                    }
                }
            }
        }
    }

    private suspend fun prefetchOtherBranchTokens(
        username: String,
        password: String,
        primaryBranchId: String
    ) {
        for (branch in branchManager.allBranches()) {
            if (branch.id == primaryBranchId) continue
            if (!sessionManager.tokenForBranch(branch.id).isNullOrBlank()) continue
            val config = branchManager.syncConfigForBranch(branch.id) ?: continue
            when (val result = authRepository.loginOnBranch(config, username, password)) {
                is LoginResult.Success ->
                    sessionManager.saveTokenForBranch(branch.id, result.token)
                else -> Unit
            }
        }
    }

    private suspend fun attemptLogin(
        username: String,
        password: String,
        triedFallback: Boolean
    ): LoginResult {
        when (val result = authRepository.login(username, password)) {
            is LoginResult.Unavailable -> if (!triedFallback && branchManager.allBranches().size <= 1) {
                // Con varias sucursales cada una tiene su propia URL; no saltar a
                // fallbackUrls globales (emulador/LAN) o se mezclan instancias.
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

    private fun prepareBranchConnection(branchId: String) {
        val alreadyConnected = sessionManager.activeBranchId() == branchId &&
            SyncConfig.load(appContext)?.branchId == branchId
        branchManager.activateBranch(branchId)
        branchManager.syncConfigForBranch(branchId)?.let { config ->
            if (alreadyConnected) {
                refreshServerLabel()
            } else {
                applyServerConfig(config)
            }
        }
    }

    private fun applyServerConfig(config: SyncConfig, clearError: Boolean = false) {
        CloudConfigStore.save(appContext, config)
        restartCloudSync(null)
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
            branchManager: BranchManager,
            appContext: Context,
            cloudSyncStatus: StateFlow<CloudSyncInfo>,
            restartCloudSync: (String?) -> Unit
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return LoginViewModel(
                    authRepository,
                    sessionManager,
                    branchManager,
                    appContext.applicationContext,
                    cloudSyncStatus,
                    restartCloudSync
                ) as T
            }
        }
    }
}
