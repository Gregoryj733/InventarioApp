package com.inventario.app.ui.batteryfinder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.inventario.app.data.battery.BatteryInventoryMatcher
import com.inventario.app.data.battery.BatteryRecommendation
import com.inventario.app.data.entity.BatteryFinderEntry
import com.inventario.app.data.entity.Product
import com.inventario.app.data.repository.BatteryFinderRepository
import com.inventario.app.data.repository.InventoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BatteryFinderUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val marcas: List<String> = emptyList(),
    val modelos: List<String> = emptyList(),
    val anios: List<String> = emptyList(),
    val selectedMarca: String? = null,
    val selectedModelo: String? = null,
    val selectedAnio: String? = null,
    val recommendations: List<BatteryRecommendation> = emptyList(),
    val bcvRate: Double? = null
) {
    val hasResult: Boolean get() = selectedAnio != null
}

/**
 * Módulo "Validar Batería": replica el buscador "Encuentra fácilmente la
 * batería que tu vehículo necesita" de duncan.com.ve. El catálogo completo
 * (marca/modelo/año -> batería) se descarga una sola vez y todo el filtrado
 * en cascada ocurre en memoria, igual que en el sitio de origen.
 */
class BatteryFinderViewModel(
    private val batteryFinderRepository: BatteryFinderRepository,
    private val inventoryRepository: InventoryRepository
) : ViewModel() {
    private val _state = MutableStateFlow(BatteryFinderUiState())
    val state: StateFlow<BatteryFinderUiState> = _state.asStateFlow()

    private var entries: List<BatteryFinderEntry> = emptyList()
    private var inventoryProducts: List<Product> = emptyList()

    init {
        load()
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

    /** Limpia selección al volver a entrar al módulo (evita resultados anteriores). */
    fun onScreenVisible() {
        if (_state.value.loading) return
        clearSelection()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val loaded = runCatching { batteryFinderRepository.fetchAll() }.getOrDefault(emptyList())
            entries = loaded
            if (loaded.isEmpty()) {
                _state.update {
                    it.copy(
                        loading = false,
                        error = "No se pudo cargar el catálogo de baterías. Verifica tu conexión e intenta de nuevo."
                    )
                }
                return@launch
            }
            _state.update {
                it.copy(
                    loading = false,
                    error = null,
                    marcas = distinctSorted(loaded.map { entry -> entry.marca }),
                    selectedMarca = null,
                    selectedModelo = null,
                    selectedAnio = null,
                    modelos = emptyList(),
                    anios = emptyList(),
                    recommendations = emptyList()
                )
            }
        }
    }

    fun onMarcaSelected(marca: String) {
        val normalizedMarca = marca.trim().lowercase()
        val modelos = distinctSorted(
            entries.filter { it.marca == normalizedMarca }.map { it.modelo }
        )
        _state.update {
            it.copy(
                selectedMarca = normalizedMarca,
                selectedModelo = null,
                selectedAnio = null,
                modelos = modelos,
                anios = emptyList(),
                recommendations = emptyList()
            )
        }
    }

    fun onModeloSelected(modelo: String) {
        val marca = _state.value.selectedMarca ?: return
        val normalizedModelo = modelo.trim().lowercase()
        val anios = distinctSorted(
            entries.filter { it.marca == marca && it.modelo == normalizedModelo }.map { it.anio }
        )
        _state.update {
            it.copy(
                selectedModelo = normalizedModelo,
                selectedAnio = null,
                anios = anios,
                recommendations = emptyList()
            )
        }
    }

    fun onAnioSelected(anio: String) {
        val marca = _state.value.selectedMarca ?: return
        val modelo = _state.value.selectedModelo ?: return
        val normalizedAnio = anio.trim()
        val codes = entries
            .filter { it.marca == marca && it.modelo == modelo && it.anio == normalizedAnio }
            .map { adjustBatteryAmperageForDisplay(it.bateria) }
            .distinct()
        val recommendations = codes.map { code ->
            BatteryInventoryMatcher.recommend(code, inventoryProducts)
        }
        _state.update {
            it.copy(selectedAnio = normalizedAnio, recommendations = recommendations)
        }
    }

    fun clearSelection() {
        _state.update {
            it.copy(
                selectedMarca = null,
                selectedModelo = null,
                selectedAnio = null,
                modelos = emptyList(),
                anios = emptyList(),
                recommendations = emptyList()
            )
        }
    }

    private fun refreshStockMatches() {
        val current = _state.value
        if (current.recommendations.isEmpty()) return
        val refreshed = current.recommendations.map { recommendation ->
            BatteryInventoryMatcher.recommend(recommendation.code, inventoryProducts)
        }
        _state.update { it.copy(recommendations = refreshed) }
    }

    private fun distinctSorted(values: List<String>): List<String> =
        values.distinct().sorted()

    /**
     * El catálogo embebido queda ~50 A por debajo de la recomendación oficial
     * de Duncan; solo ajustamos el valor mostrado al usuario, no los datos base.
     */
    private fun adjustBatteryAmperageForDisplay(code: String): String {
        val trimmed = code.trim().lowercase()
        val dash = trimmed.lastIndexOf('-')
        if (dash <= 0) return trimmed.uppercase()
        val amps = trimmed.substring(dash + 1).toIntOrNull() ?: return trimmed.uppercase()
        return "${trimmed.substring(0, dash + 1)}${amps + DUNCAN_AMPERAGE_OFFSET}".uppercase()
    }

    companion object {
        private const val DUNCAN_AMPERAGE_OFFSET = 50

        fun factory(
            batteryFinderRepository: BatteryFinderRepository,
            inventoryRepository: InventoryRepository
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return BatteryFinderViewModel(batteryFinderRepository, inventoryRepository) as T
            }
        }
    }
}
