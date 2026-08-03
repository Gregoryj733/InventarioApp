package com.inventario.app.ui.hub

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.inventario.app.data.bcv.BcvRateFetcher
import com.inventario.app.data.entity.UserRole
import com.inventario.app.data.repository.InventoryRepository
import com.inventario.app.data.session.SessionManager
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
    val currentDate: String = ""
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
            }
        }
        refreshBcv()
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
