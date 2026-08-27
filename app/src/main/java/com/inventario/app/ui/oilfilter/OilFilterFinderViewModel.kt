package com.inventario.app.ui.oilfilter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.inventario.app.data.entity.OilFilterEntry
import com.inventario.app.data.entity.Product
import com.inventario.app.data.oilfilter.OilFilterRecommendation
import com.inventario.app.data.oilfilter.OilFilterStockIndex
import com.inventario.app.data.repository.InventoryRepository
import com.inventario.app.data.repository.OilFilterCatalogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class OilFilterFinderUiState(
    val loading: Boolean = true,
    val searching: Boolean = false,
    val error: String? = null,
    val catalogCount: Int = 0,
    val query: String = "",
    val results: List<OilFilterRecommendation> = emptyList(),
    val extraStock: List<Product> = emptyList(),
    val hasSearched: Boolean = false,
    val bcvRate: Double? = null
)

class OilFilterFinderViewModel(
    private val catalogRepository: OilFilterCatalogRepository,
    private val inventoryRepository: InventoryRepository
) : ViewModel() {
    private val _state = MutableStateFlow(OilFilterFinderUiState())
    val state: StateFlow<OilFilterFinderUiState> = _state.asStateFlow()
    private var searchJob: Job? = null

    @Volatile
    private var stockIndex = OilFilterStockIndex(emptyList())

    init {
        load()
        viewModelScope.launch {
            inventoryRepository.observeAllProducts().collect { products ->
                stockIndex = withContext(Dispatchers.Default) { OilFilterStockIndex(products) }
                refreshStockMatches()
            }
        }
        viewModelScope.launch {
            inventoryRepository.observeMeta().collect { meta ->
                _state.update { it.copy(bcvRate = meta?.bcvRate) }
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val count = runCatching { catalogRepository.ensureReady() }.getOrDefault(0)
            if (count == 0) {
                _state.update {
                    it.copy(
                        loading = false,
                        error = "No se pudo cargar el catálogo de filtros de aceite."
                    )
                }
                return@launch
            }
            _state.update {
                it.copy(loading = false, error = null, catalogCount = count)
            }
        }
    }

    fun onQueryChange(value: String) {
        _state.update { it.copy(query = value) }
        searchJob?.cancel()
        val trimmed = value.trim()
        if (trimmed.length < 2) {
            _state.update {
                it.copy(results = emptyList(), extraStock = emptyList(), hasSearched = false, searching = false)
            }
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            runSearch(trimmed)
        }
    }

    fun search() {
        searchJob?.cancel()
        runSearch(_state.value.query.trim())
    }

    fun clear() {
        searchJob?.cancel()
        _state.update {
            it.copy(
                query = "",
                results = emptyList(),
                extraStock = emptyList(),
                hasSearched = false,
                searching = false
            )
        }
    }

    private fun runSearch(query: String) {
        if (query.length < 2) {
            _state.update {
                it.copy(results = emptyList(), extraStock = emptyList(), hasSearched = false, searching = false)
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(searching = true) }
            val decorated = withContext(Dispatchers.Default) {
                val catalog = catalogRepository.search(query)
                decorate(catalog, query, stockIndex)
            }
            _state.update {
                it.copy(
                    searching = false,
                    hasSearched = true,
                    results = decorated.first,
                    extraStock = decorated.second
                )
            }
        }
    }

    private fun refreshStockMatches() {
        val current = _state.value
        if (!current.hasSearched) return
        viewModelScope.launch {
            val decorated = withContext(Dispatchers.Default) {
                decorate(current.results.map { it.entry }, current.query, stockIndex)
            }
            _state.update { it.copy(results = decorated.first, extraStock = decorated.second) }
        }
    }

    private fun decorate(
        entries: List<OilFilterEntry>,
        query: String,
        index: OilFilterStockIndex
    ): Pair<List<OilFilterRecommendation>, List<Product>> {
        val recommendations = ArrayList<OilFilterRecommendation>(entries.size)
        for (entry in entries) {
            recommendations.add(index.recommend(entry))
        }
        recommendations.sortByDescending { it.stockMatch.inStock }
        val usedIds = HashSet<Long>()
        for (rec in recommendations) {
            rec.stockMatch.product?.let { usedIds.add(it.id) }
            rec.otherStockFilters.forEach { usedIds.add(it.id) }
            rec.oilStock.forEach { usedIds.add(it.id) }
        }
        val extra = index.searchStockFilters(query, usedIds)
        return recommendations to extra
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 120L

        fun factory(
            catalogRepository: OilFilterCatalogRepository,
            inventoryRepository: InventoryRepository
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return OilFilterFinderViewModel(catalogRepository, inventoryRepository) as T
            }
        }
    }
}
