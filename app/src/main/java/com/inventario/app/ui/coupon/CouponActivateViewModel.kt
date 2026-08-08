package com.inventario.app.ui.coupon

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.inventario.app.data.entity.DiscountTicket
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
            _state.update { it.copy(loading = true, error = null) }
            inventoryRepository.activateDiscountTicket(code)
                .onSuccess { ticket ->
                    _state.update {
                        it.copy(loading = false, lastActivated = ticket, error = null)
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
