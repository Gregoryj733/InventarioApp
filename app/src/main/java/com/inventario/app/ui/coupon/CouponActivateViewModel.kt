package com.inventario.app.ui.coupon

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.inventario.app.data.entity.DiscountTicket
import com.inventario.app.data.entity.isExecutable
import com.inventario.app.data.entity.isExecutedPendingSale
import com.inventario.app.data.entity.isExpired
import com.inventario.app.data.entity.isFullyConsumed
import com.inventario.app.data.entity.isIssued
import com.inventario.app.data.entity.isVoided
import com.inventario.app.data.repository.InventoryRepository
import com.inventario.app.data.sync.toUserMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CouponActivateUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val infoMessage: String? = null,
    val lastActivated: DiscountTicket? = null
)

class CouponActivateViewModel(
    private val inventoryRepository: InventoryRepository
) : ViewModel() {
    private val _state = MutableStateFlow(CouponActivateUiState())
    val state: StateFlow<CouponActivateUiState> = _state.asStateFlow()

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("es", "VE"))

    fun formatDate(millis: Long): String = dateFormat.format(Date(millis))

    fun activateCode(rawCode: String) {
        val code = rawCode.trim()
        if (code.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, infoMessage = null, lastActivated = null) }
            inventoryRepository.findDiscountTicket(code)
                .onSuccess { ticket ->
                    when {
                        ticket == null -> {
                            _state.update {
                                it.copy(loading = false, error = "Cupón no encontrado. Verifica el código.")
                            }
                        }
                        ticket.isVoided() -> {
                            _state.update {
                                it.copy(loading = false, error = "Este cupón fue anulado.")
                            }
                        }
                        ticket.isFullyConsumed() -> {
                            _state.update {
                                it.copy(loading = false, error = "Este cupón ya fue utilizado.")
                            }
                        }
                        ticket.isIssued() -> activateIssuedTicket(code)
                        ticket.isExecutable() || ticket.isExecutedPendingSale() -> {
                            _state.update {
                                it.copy(
                                    loading = false,
                                    infoMessage = "Este cupón ya está activo. Para ejecutarlo y aplicar el descuento, " +
                                        "escanea el código nuevamente desde el carrito de compras e ingresa el teléfono."
                                )
                            }
                        }
                        ticket.isExpired() -> {
                            _state.update {
                                it.copy(
                                    loading = false,
                                    error = ticket.expiresAt?.let { expires ->
                                        "Este cupón expiró el ${formatDate(expires)}."
                                    } ?: "Este cupón está expirado."
                                )
                            }
                        }
                        else -> {
                            _state.update {
                                it.copy(loading = false, error = "Este cupón no puede activarse.")
                            }
                        }
                    }
                }
                .onFailure { err ->
                    _state.update {
                        it.copy(
                            loading = false,
                            error = err.toUserMessage("No se pudo consultar el cupón.")
                        )
                    }
                }
        }
    }

    private suspend fun activateIssuedTicket(code: String) {
        inventoryRepository.activateDiscountTicket(code)
            .onSuccess { ticket ->
                _state.update {
                    it.copy(loading = false, lastActivated = ticket, error = null, infoMessage = null)
                }
            }
            .onFailure { err ->
                _state.update {
                    it.copy(
                        loading = false,
                        error = err.toUserMessage("No se pudo activar el cupón.")
                    )
                }
            }
    }

    companion object {
        fun factory(inventoryRepository: InventoryRepository) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return CouponActivateViewModel(inventoryRepository) as T
            }
        }
    }
}
