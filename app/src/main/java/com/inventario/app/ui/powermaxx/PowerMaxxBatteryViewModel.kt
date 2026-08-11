package com.inventario.app.ui.powermaxx

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.inventario.app.data.acpower.AcPowerBatteryProduct
import com.inventario.app.data.acpower.AcPowerBatteryRepository
import com.inventario.app.data.battery.BatteryAmperage
import com.inventario.app.data.battery.BatteryInventoryMatcher
import com.inventario.app.data.battery.BatteryStockMatch
import com.inventario.app.data.entity.Product
import com.inventario.app.data.repository.InventoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PowerMaxxSearchMode {
    VEHICLE,
    BATTERY_CODE
}

data class PowerMaxxBatteryResult(
    val product: AcPowerBatteryProduct,
    val stockMatch: BatteryStockMatch
)

data class PowerMaxxBatteryUiState(
    val loading: Boolean = true,
    val loadingModelos: Boolean = false,
    val searching: Boolean = false,
    val error: String? = null,
    val searchMode: PowerMaxxSearchMode = PowerMaxxSearchMode.VEHICLE,
    val marcas: List<String> = emptyList(),
    val modelos: List<String> = emptyList(),
    val selectedMarca: String? = null,
    val selectedModelo: String? = null,
    val batteryCodeQuery: String = "",
    val results: List<PowerMaxxBatteryResult> = emptyList(),
    val hasSearched: Boolean = false,
    val bcvRate: Double? = null
)

/**
 * Módulo «Validador Batería Power Maxx»: replica el buscador de
 * [acpowervzla.com](https://acpowervzla.com/#encuentra-tu-bateria) con pestañas
 * Vehículo/Industria y Código de batería.
 */
class PowerMaxxBatteryViewModel(
    private val repository: AcPowerBatteryRepository,
    private val inventoryRepository: InventoryRepository
) : ViewModel() {
    private val _state = MutableStateFlow(PowerMaxxBatteryUiState())
    val state: StateFlow<PowerMaxxBatteryUiState> = _state.asStateFlow()

    private var inventoryProducts: List<Product> = emptyList()

    init {
        loadMarcas()
        viewModelScope.launch {
            inventoryRepository.observeAllProducts().collect { products ->
                inventoryProducts = products
                refreshStockMatches()
            }
        }
        viewModelScope.launch {
            inventoryRepository.observeMeta().collect { meta ->
                _state.update { it.copy(bcvRate = meta?.bcvRate) }
            }
        }
    }

    fun onScreenVisible() {
        if (_state.value.loading) return
        _state.update {
            it.copy(
                selectedMarca = null,
                selectedModelo = null,
                modelos = emptyList(),
                batteryCodeQuery = "",
                results = emptyList(),
                hasSearched = false,
                error = null
            )
        }
    }

    fun setSearchMode(mode: PowerMaxxSearchMode) {
        _state.update {
            it.copy(
                searchMode = mode,
                results = emptyList(),
                hasSearched = false,
                error = null
            )
        }
    }

    fun onBatteryCodeChanged(value: String) {
        _state.update { it.copy(batteryCodeQuery = value.uppercase(), error = null) }
    }

    fun loadMarcas() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val marcas = runCatching { repository.fetchMarcas() }.getOrDefault(emptyList())
            _state.update {
                if (marcas.isEmpty()) {
                    it.copy(
                        loading = false,
                        error = "No se pudo cargar el catálogo de marcas. Verifica tu conexión e intenta de nuevo."
                    )
                } else {
                    it.copy(loading = false, marcas = marcas, error = null)
                }
            }
        }
    }

    fun onMarcaSelected(marca: String) {
        _state.update {
            it.copy(
                selectedMarca = marca,
                selectedModelo = null,
                modelos = emptyList(),
                results = emptyList(),
                hasSearched = false,
                loadingModelos = true,
                error = null
            )
        }
        viewModelScope.launch {
            val modelos = runCatching { repository.fetchModelos(marca) }
                .getOrElse {
                    _state.update { state ->
                        state.copy(
                            loadingModelos = false,
                            error = "No se pudieron cargar los modelos. Intenta de nuevo."
                        )
                    }
                    return@launch
                }
            _state.update {
                it.copy(modelos = modelos, loadingModelos = false, error = null)
            }
        }
    }

    fun onModeloSelected(modelo: String) {
        _state.update {
            it.copy(selectedModelo = modelo, results = emptyList(), hasSearched = false, error = null)
        }
    }

    fun search() {
        val current = _state.value
        when (current.searchMode) {
            PowerMaxxSearchMode.VEHICLE -> searchByVehicle(current)
            PowerMaxxSearchMode.BATTERY_CODE -> searchByCode(current.batteryCodeQuery)
        }
    }

    private fun searchByVehicle(current: PowerMaxxBatteryUiState) {
        val marca = current.selectedMarca
        val modelo = current.selectedModelo
        if (marca.isNullOrBlank() || modelo.isNullOrBlank()) {
            _state.update { it.copy(error = "Selecciona marca y modelo antes de buscar.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(searching = true, error = null) }
            val products = runCatching { repository.searchByVehicle(marca, modelo) }
                .getOrElse {
                    _state.update { state ->
                        state.copy(
                            searching = false,
                            error = "No se pudo consultar AC Power. Verifica tu conexión."
                        )
                    }
                    return@launch
                }
            _state.update {
                it.copy(
                    searching = false,
                    results = products.toResults(),
                    hasSearched = true,
                    error = null
                )
            }
        }
    }

    private fun searchByCode(code: String) {
        if (code.trim().length < 2) {
            _state.update { it.copy(error = "Ingresa al menos 2 caracteres del código de batería.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(searching = true, error = null) }
            val products = runCatching { repository.searchByCode(code) }
                .getOrElse {
                    _state.update { state ->
                        state.copy(
                            searching = false,
                            error = "No se pudo validar el código. Verifica tu conexión."
                        )
                    }
                    return@launch
                }
            _state.update {
                it.copy(
                    searching = false,
                    results = products.toResults(),
                    hasSearched = true,
                    error = null
                )
            }
        }
    }

    fun clearVehicleSelection() {
        _state.update {
            it.copy(
                selectedMarca = null,
                selectedModelo = null,
                modelos = emptyList(),
                results = emptyList(),
                hasSearched = false,
                error = null
            )
        }
    }

    private fun refreshStockMatches() {
        val current = _state.value
        if (current.results.isEmpty()) return
        _state.update { it.copy(results = current.results.map { it.refreshStock() }) }
    }

    private fun List<AcPowerBatteryProduct>.toResults(): List<PowerMaxxBatteryResult> =
        map { product ->
            PowerMaxxBatteryResult(
                product = product,
                stockMatch = BatteryInventoryMatcher.match(
                    validatorAmperage = BatteryAmperage.fromBatteryCode(product.code),
                    products = inventoryProducts
                )
            )
        }

    private fun PowerMaxxBatteryResult.refreshStock(): PowerMaxxBatteryResult =
        copy(
            stockMatch = BatteryInventoryMatcher.match(
                validatorAmperage = BatteryAmperage.fromBatteryCode(product.code),
                products = inventoryProducts
            )
        )

    companion object {
        fun factory(
            repository: AcPowerBatteryRepository,
            inventoryRepository: InventoryRepository
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return PowerMaxxBatteryViewModel(repository, inventoryRepository) as T
            }
        }
    }
}
