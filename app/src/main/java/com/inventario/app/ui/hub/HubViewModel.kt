package com.inventario.app.ui.hub

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.inventario.app.data.bcv.BcvRateFetcher
import com.inventario.app.data.entity.CashClosingAlertType
import com.inventario.app.data.entity.CashClosingStatus
import com.inventario.app.data.entity.UserRole
import com.inventario.app.data.repository.InventoryRepository
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
    private val bcvRateFetcher: BcvRateFetcher
) : ViewModel() {
    private val _state = MutableStateFlow(HubUiState())
    val state: StateFlow<HubUiState> = _state.asStateFlow()

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale("es", "VE"))
    private val bcvRateFormat = NumberFormat.getNumberInstance(Locale("es", "VE")).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }

    init {
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
            // El badge de "Flujo Aprobación" (cierres pendientes) y el aviso
            // de aprobado/rechazado deben reflejar lo que hace CUALQUIER
            // usuario (Admin o Supervisor) en tiempo real, sin depender de
            // reabrir la pantalla.
            inventoryRepository.observeCloudEvents().collect { event ->
                if (event is CloudEvent.CashClosings) {
                    refreshClosingAlerts()
                }
            }
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
            bcvRateFetcher: BcvRateFetcher
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return HubViewModel(inventoryRepository, sessionManager, bcvRateFetcher) as T
            }
        }
    }
}
