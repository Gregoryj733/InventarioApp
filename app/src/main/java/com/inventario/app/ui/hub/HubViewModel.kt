package com.inventario.app.ui.hub

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.inventario.app.data.bcv.BcvRateFetcher
import com.inventario.app.data.branch.BranchConfig
import com.inventario.app.data.branch.BranchManager
import com.inventario.app.data.entity.CashClosingAlertType
import com.inventario.app.data.entity.CashClosingStatus
import com.inventario.app.data.entity.UserRole
import com.inventario.app.data.entity.canSwitchBranch
import com.inventario.app.data.repository.AuthRepository
import com.inventario.app.data.repository.InventoryRepository
import com.inventario.app.data.repository.LoginResult
import com.inventario.app.data.session.SessionManager
import com.inventario.app.data.sync.CloudEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.round

data class HubUiState(
    val username: String = "",
    val role: UserRole = UserRole.CONSULTA,
    val activeBranchLabel: String = "",
    val canSwitchBranch: Boolean = false,
    val availableBranches: List<BranchConfig> = emptyList(),
    val showBranchSwitchDialog: Boolean = false,
    val branchSwitchLoading: Boolean = false,
    val branchSwitchError: String? = null,
    val pendingReauthBranchId: String? = null,
    val reauthPassword: String = "",
    val bcvRate: Double? = null,
    val bcvLabel: String = "Tasa BCV: —",
    val bcvRefreshing: Boolean = false,
    val currentDate: String = "",
    val cashClosingAlert: CashClosingAlertType? = null,
    val pendingReportsCount: Int = 0
)

class HubViewModel(
    private val inventoryRepository: InventoryRepository,
    private val sessionManager: SessionManager,
    private val branchManager: BranchManager,
    private val authRepository: AuthRepository,
    private val bcvRateFetcher: BcvRateFetcher,
    private val switchBranch: (String) -> Boolean,
    private val onBranchSwitched: () -> Unit
) : ViewModel() {
    private val _state = MutableStateFlow(HubUiState())
    val state: StateFlow<HubUiState> = _state.asStateFlow()

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale("es", "VE"))
    private val bcvRateFormat = NumberFormat.getNumberInstance(Locale("es", "VE")).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }

    init {
        refreshBranchInfo()
        _state.update {
            it.copy(
                username = sessionManager.username().orEmpty(),
                role = sessionManager.role() ?: UserRole.CONSULTA,
                currentDate = dateFormat.format(Date())
            )
        }
        viewModelScope.launch {
            inventoryRepository.observeMeta().collect { meta ->
                val rate = meta?.bcvRate?.let(::roundBcvRate)
                _state.update { state ->
                    state.copy(
                        bcvRate = rate,
                        bcvLabel = if (rate != null) {
                            "Tasa BCV: Bs ${bcvRateFormat.format(rate)}"
                        } else {
                            "Tasa BCV: sin datos"
                        }
                    )
                }
                if (BcvRateFetcher.isStale(meta?.bcvFetchedAt)) {
                    refreshBcv()
                }
            }
        }
        refreshBcv()
        refreshClosingAlerts()
        viewModelScope.launch {
            inventoryRepository.observeCloudEvents().collect { event ->
                if (event is CloudEvent.CashClosings) {
                    refreshClosingAlerts()
                }
            }
        }
    }

    fun openBranchSwitchDialog() {
        _state.update {
            it.copy(
                showBranchSwitchDialog = true,
                branchSwitchError = null,
                pendingReauthBranchId = null,
                reauthPassword = ""
            )
        }
    }

    fun dismissBranchSwitchDialog() {
        _state.update {
            it.copy(
                showBranchSwitchDialog = false,
                branchSwitchError = null,
                pendingReauthBranchId = null,
                reauthPassword = "",
                branchSwitchLoading = false
            )
        }
    }

    fun onReauthPasswordChange(value: String) {
        _state.update { it.copy(reauthPassword = value, branchSwitchError = null) }
    }

    fun requestBranchSwitch(branchId: String) {
        if (branchId == sessionManager.activeBranchId()) {
            dismissBranchSwitchDialog()
            return
        }
        if (inventoryRepository.hasPendingOfflineOrders()) {
            _state.update {
                it.copy(
                    branchSwitchError = "Hay pedidos pendientes de sincronizar. " +
                        "Espera la conexión o confírmalos antes de cambiar de sucursal."
                )
            }
            return
        }
        if (branchManager.requiresReauth(branchId)) {
            _state.update {
                it.copy(
                    pendingReauthBranchId = branchId,
                    reauthPassword = "",
                    branchSwitchError = null
                )
            }
            return
        }
        performBranchSwitch(branchId, password = null)
    }

    fun confirmBranchReauth() {
        val branchId = _state.value.pendingReauthBranchId ?: return
        val password = _state.value.reauthPassword
        if (password.isBlank()) {
            _state.update { it.copy(branchSwitchError = "Ingresa tu contraseña.") }
            return
        }
        performBranchSwitch(branchId, password)
    }

    private fun performBranchSwitch(branchId: String, password: String?) {
        viewModelScope.launch {
            _state.update { it.copy(branchSwitchLoading = true, branchSwitchError = null) }
            if (password != null) {
                branchManager.activateBranch(branchId)
                switchBranch(branchId)
                val username = sessionManager.username().orEmpty()
                when (val result = authRepository.login(username, password)) {
                    is LoginResult.Success -> {
                        branchManager.saveBranchSession(
                            branchId,
                            sessionManager.token().orEmpty()
                        )
                    }
                    LoginResult.InvalidCredentials -> {
                        _state.update {
                            it.copy(
                                branchSwitchLoading = false,
                                branchSwitchError = "Contraseña incorrecta."
                            )
                        }
                        return@launch
                    }
                    LoginResult.Inactive -> {
                        _state.update {
                            it.copy(
                                branchSwitchLoading = false,
                                branchSwitchError = "Usuario desactivado en esta sucursal."
                            )
                        }
                        return@launch
                    }
                    is LoginResult.Unavailable -> {
                        _state.update {
                            it.copy(
                                branchSwitchLoading = false,
                                branchSwitchError = result.message
                            )
                        }
                        return@launch
                    }
                }
            } else {
                val switched = switchBranch(branchId)
                if (!switched) {
                    _state.update {
                        it.copy(
                            branchSwitchLoading = false,
                            branchSwitchError = "No se pudo cambiar de sucursal. " +
                                "Verifica que no haya pedidos pendientes."
                        )
                    }
                    return@launch
                }
            }
            refreshBranchInfo()
            refreshClosingAlerts()
            _state.update {
                it.copy(
                    branchSwitchLoading = false,
                    showBranchSwitchDialog = false,
                    pendingReauthBranchId = null,
                    reauthPassword = "",
                    branchSwitchError = null
                )
            }
            onBranchSwitched()
        }
    }

    private fun refreshBranchInfo() {
        val role = sessionManager.role() ?: UserRole.CONSULTA
        _state.update {
            it.copy(
                activeBranchLabel = branchManager.getActiveBranch()?.label.orEmpty(),
                canSwitchBranch = role.canSwitchBranch() && branchManager.allBranches().size > 1,
                availableBranches = if (role.canSwitchBranch()) {
                    branchManager.allBranches()
                } else {
                    emptyList()
                }
            )
        }
    }

    fun refreshClosingAlerts() {
        viewModelScope.launch {
            val username = sessionManager.username().orEmpty()
            val alert = inventoryRepository.cashClosingAlertForUser(username)
            val latest = inventoryRepository.latestClosingToday(username)
            val ackId = sessionManager.lastAcknowledgedClosingId(username)
            val visibleAlert = when {
                alert == CashClosingAlertType.REJECTED_RESUBMIT &&
                    latest?.status == CashClosingStatus.REJECTED &&
                    latest.id > ackId -> CashClosingAlertType.REJECTED_RESUBMIT
                alert == CashClosingAlertType.APPROVED_SUCCESS &&
                    latest?.status == CashClosingStatus.APPROVED &&
                    latest.id > ackId -> CashClosingAlertType.APPROVED_SUCCESS
                else -> null
            }
            val pendingCount = inventoryRepository.pendingClosingsCount()
            _state.update {
                it.copy(
                    cashClosingAlert = visibleAlert,
                    pendingReportsCount = pendingCount
                )
            }
        }
    }

    fun refreshBcv() {
        viewModelScope.launch {
            _state.update {
                it.copy(bcvRefreshing = true, currentDate = dateFormat.format(Date()))
            }
            bcvRateFetcher.fetchUsdRate()
                .onSuccess { rate ->
                    val rounded = roundBcvRate(rate)
                    inventoryRepository.saveBcvRate(rounded)
                    _state.update {
                        it.copy(
                            bcvRefreshing = false,
                            bcvRate = rounded,
                            bcvLabel = "Tasa BCV: Bs ${bcvRateFormat.format(rounded)}"
                        )
                    }
                }
                .onFailure {
                    _state.update { it.copy(bcvRefreshing = false) }
                }
        }
    }

    private fun roundBcvRate(rate: Double): Double = round(rate * 100) / 100.0

    companion object {
        fun factory(
            inventoryRepository: InventoryRepository,
            sessionManager: SessionManager,
            branchManager: BranchManager,
            authRepository: AuthRepository,
            bcvRateFetcher: BcvRateFetcher,
            switchBranch: (String) -> Boolean,
            onBranchSwitched: () -> Unit
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return HubViewModel(
                    inventoryRepository,
                    sessionManager,
                    branchManager,
                    authRepository,
                    bcvRateFetcher,
                    switchBranch,
                    onBranchSwitched
                ) as T
            }
        }
    }
}
