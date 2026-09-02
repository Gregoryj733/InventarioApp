package com.inventario.app.ui.hub

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.inventario.app.data.bcv.BcvRateFetcher
import com.inventario.app.data.branch.BranchConfig
import com.inventario.app.data.branch.BranchManager
import com.inventario.app.data.entity.CashClosingAlertType
import com.inventario.app.data.entity.CashClosingRecord
import com.inventario.app.data.entity.CashClosingStatus
import com.inventario.app.data.entity.UserRole
import com.inventario.app.data.entity.canSwitchBranch
import com.inventario.app.data.entity.isBranchRestricted
import com.inventario.app.data.entity.canViewBranchSalesKpis
import com.inventario.app.data.entity.isGerenteProfile
import com.inventario.app.data.entity.shouldReceiveClosingExcelReminder
import com.inventario.app.data.entity.shouldShowClosingExcelReminder
import com.inventario.app.data.excel.CashClosingExcelExporter
import com.inventario.app.data.excel.CashClosingHistoryExport
import com.inventario.app.data.repository.AuthRepository
import com.inventario.app.data.repository.BranchSalesKpiRepository
import com.inventario.app.data.repository.InventoryRepository
import com.inventario.app.data.repository.LoginResult
import com.inventario.app.data.session.SessionManager
import com.inventario.app.data.sync.CloudEvent
import com.inventario.app.data.sync.toUserMessage
import com.inventario.app.ui.theme.AppSnackbarController
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    val activeBranchId: String = "",
    val activeBranchLabel: String = "",
    val canSwitchBranch: Boolean = false,
    val availableBranches: List<BranchConfig> = emptyList(),
    val showBranchSwitchDialog: Boolean = false,
    val branchSwitchLoading: Boolean = false,
    val branchSwitchError: String? = null,
    val pendingReauthBranchId: String? = null,
    val pendingBranchSwitchId: String? = null,
    val reauthPassword: String = "",
    val bcvRate: Double? = null,
    val bcvLabel: String = "Tasa BCV: —",
    val bcvRefreshing: Boolean = false,
    val currentDate: String = "",
    val cashClosingAlert: CashClosingAlertType? = null,
    val pendingReportsCount: Int = 0,
    val showBranchKpis: Boolean = false,
    val branchSalesKpis: List<com.inventario.app.data.repository.BranchDailySalesKpi> = emptyList(),
    val branchKpisLoading: Boolean = false,
    val showClosingExcelReminder: Boolean = false,
    val exportingClosingExcel: Boolean = false
)

class HubViewModel(
    private val inventoryRepository: InventoryRepository,
    private val sessionManager: SessionManager,
    private val branchManager: BranchManager,
    private val authRepository: AuthRepository,
    private val branchSalesKpiRepository: BranchSalesKpiRepository,
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
            refreshClosingAlerts()
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
                if (BcvRateFetcher.isStale(meta?.bcvFetchedAt) && !_state.value.branchSwitchLoading) {
                    refreshBcv()
                }
            }
        }
        refreshBcv()
        refreshClosingAlerts()
        viewModelScope.launch {
            inventoryRepository.observeCloudEvents().collect { event ->
                when (event) {
                    is CloudEvent.CashClosings -> refreshClosingAlerts()
                    is CloudEvent.Sales -> if (_state.value.showBranchKpis) refreshBranchSalesKpis()
                    else -> Unit
                }
            }
        }
        refreshBranchSalesKpis()
    }

    private fun refreshBranchSalesKpis(showLoading: Boolean = true) {
        val role = sessionManager.role() ?: return
        if (!role.canViewBranchSalesKpis()) {
            _state.update { it.copy(showBranchKpis = false, branchSalesKpis = emptyList()) }
            return
        }
        viewModelScope.launch {
            loadBranchSalesKpis(showLoading = showLoading)
        }
    }

    private suspend fun loadBranchSalesKpis(showLoading: Boolean) {
        val role = sessionManager.role() ?: return
        if (!role.canViewBranchSalesKpis()) {
            _state.update { it.copy(showBranchKpis = false, branchSalesKpis = emptyList()) }
            return
        }
        if (showLoading) {
            _state.update { it.copy(branchKpisLoading = true, showBranchKpis = true) }
        }
        val kpis = branchSalesKpiRepository.loadTodayPerBranch(_state.value.bcvRate)
        _state.update { it.copy(branchSalesKpis = kpis, branchKpisLoading = false, showBranchKpis = true) }
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
        if (_state.value.branchSwitchLoading) return
        _state.update {
            it.copy(
                showBranchSwitchDialog = false,
                branchSwitchError = null,
                pendingReauthBranchId = null,
                pendingBranchSwitchId = null,
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
        val role = sessionManager.role() ?: return
        if (role.isBranchRestricted()) {
            _state.update {
                it.copy(branchSwitchError = "Tu perfil no puede cambiar de sucursal.")
            }
            return
        }
        val branch = branchManager.configFor(branchId)
        if (branch == null || !branchManager.canAccessBranch(role, sessionManager.sucursal(), branch)) {
            val label = branch?.chipLabel ?: "esa sucursal"
            _state.update {
                it.copy(branchSwitchError = "No tienes acceso a $label.")
            }
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    branchSwitchLoading = true,
                    branchSwitchError = null,
                    pendingBranchSwitchId = branchId
                )
            }
            if (password != null) {
                val username = sessionManager.username().orEmpty()
                val config = branchManager.syncConfigForBranch(branchId)
                if (config == null) {
                    _state.update {
                        it.copy(
                            branchSwitchLoading = false,
                            branchSwitchError = "Sucursal no configurada. Contacta al administrador."
                        )
                    }
                    return@launch
                }
                when (val result = authRepository.loginOnBranch(config, username, password)) {
                    is LoginResult.Success -> {
                        branchManager.saveBranchSession(branchId, result.token)
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
            finalizeBranchSwitch()
            _state.update {
                it.copy(
                    branchSwitchLoading = false,
                    showBranchSwitchDialog = false,
                    pendingReauthBranchId = null,
                    pendingBranchSwitchId = null,
                    reauthPassword = "",
                    branchSwitchError = null
                )
            }
            onBranchSwitched()
        }
    }

    private suspend fun finalizeBranchSwitch() {
        refreshBranchInfo()
        _state.update {
            it.copy(
                branchSalesKpis = emptyList(),
                branchKpisLoading = false,
                bcvRefreshing = false,
                cashClosingAlert = null,
                pendingReportsCount = 0,
                showClosingExcelReminder = false
            )
        }
        coroutineScope {
            val closingAlerts = async { loadClosingAlertsIntoState() }
            val bcv = async { refreshBcvInternal(showIndicator = false) }
            val kpis = async { loadBranchSalesKpis(showLoading = false) }
            closingAlerts.await()
            bcv.await()
            kpis.await()
        }
    }

    private fun refreshBranchInfo() {
        val role = sessionManager.role() ?: UserRole.CONSULTA
        if (role.isBranchRestricted()) {
            branchManager.enforceBranchIsolation(role, sessionManager.sucursal())
        }
        _state.update {
            it.copy(
                activeBranchId = branchManager.getActiveBranch()?.id.orEmpty(),
                activeBranchLabel = branchManager.getActiveBranch()?.chipLabel.orEmpty(),
                canSwitchBranch = role.canSwitchBranch() && branchManager.allBranches().size > 1,
                availableBranches = if (role.canSwitchBranch()) {
                    branchManager.allBranches()
                } else {
                    emptyList()
                }
            )
        }
    }

    private var closingHistoryForExport: List<CashClosingRecord> = emptyList()

    fun suggestedClosingExportFileName(): String = CashClosingExcelExporter.suggestedFileName()

    suspend fun prepareClosingExcelExport(): Boolean {
        val username = sessionManager.username().orEmpty()
        if (!shouldShowClosingExcelReminder(
                role = sessionManager.role(),
                username = username,
                hasExportedToday = sessionManager.hasExportedClosingExcelToday(username)
            )
        ) {
            return false
        }
        _state.update { it.copy(exportingClosingExcel = true) }
        val history = runCatching { inventoryRepository.listClosingHistory() }
            .getOrElse { error ->
                _state.update { it.copy(exportingClosingExcel = false) }
                AppSnackbarController.show(
                    error.toUserMessage("No se pudo cargar el historial de cierres.")
                )
                return false
            }
        if (history.isEmpty()) {
            _state.update { it.copy(exportingClosingExcel = false) }
            AppSnackbarController.show("No hay cierres para exportar.")
            return false
        }
        closingHistoryForExport = history
        return true
    }

    suspend fun exportClosingHistoryToUri(resolver: ContentResolver, uri: Uri): Result<Unit> =
        CashClosingHistoryExport.writeToUri(
            resolver = resolver,
            uri = uri,
            closings = closingHistoryForExport,
            simplifiedForGerente = _state.value.username.isGerenteProfile()
        )

    fun finishClosingExcelExport(success: Boolean, errorMessage: String? = null) {
        if (success) {
            val username = sessionManager.username().orEmpty()
            if (shouldReceiveClosingExcelReminder(sessionManager.role(), username)) {
                sessionManager.markClosingExcelExportedToday(username)
            }
        }
        closingHistoryForExport = emptyList()
        _state.update { it.copy(exportingClosingExcel = false) }
        if (success) {
            refreshClosingAlerts()
        }
        val message = when {
            success -> "Reporte Excel exportado correctamente."
            errorMessage != null -> errorMessage
            else -> "No se pudo exportar el reporte."
        }
        AppSnackbarController.show(message)
    }

    fun refreshClosingAlerts() {
        viewModelScope.launch { loadClosingAlertsIntoState() }
    }

    private suspend fun loadClosingAlertsIntoState() {
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
        val showExcelReminder = shouldShowClosingExcelReminder(
            role = sessionManager.role(),
            username = username,
            hasExportedToday = sessionManager.hasExportedClosingExcelToday(username)
        )
        _state.update {
            it.copy(
                cashClosingAlert = visibleAlert,
                pendingReportsCount = pendingCount,
                showClosingExcelReminder = showExcelReminder
            )
        }
    }

    fun refreshBcv() {
        viewModelScope.launch {
            refreshBcvInternal(showIndicator = !_state.value.branchSwitchLoading)
        }
    }

    private suspend fun refreshBcvInternal(showIndicator: Boolean) {
        if (showIndicator) {
            _state.update {
                it.copy(bcvRefreshing = true, currentDate = dateFormat.format(Date()))
            }
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
                if (_state.value.showBranchKpis) refreshBranchSalesKpis(showLoading = false)
            }
            .onFailure {
                _state.update { it.copy(bcvRefreshing = false) }
            }
    }

    private fun roundBcvRate(rate: Double): Double = round(rate * 100) / 100.0

    companion object {
        fun factory(
            inventoryRepository: InventoryRepository,
            sessionManager: SessionManager,
            branchManager: BranchManager,
            authRepository: AuthRepository,
            branchSalesKpiRepository: BranchSalesKpiRepository,
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
                    branchSalesKpiRepository,
                    bcvRateFetcher,
                    switchBranch,
                    onBranchSwitched
                ) as T
            }
        }
    }
}
