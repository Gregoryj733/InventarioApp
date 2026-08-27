package com.inventario.app.ui.oilfilter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OilBarrel
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.inventario.app.data.battery.BatteryStockMatch
import com.inventario.app.data.entity.Product
import com.inventario.app.data.oilfilter.OilFilterRecommendation
import com.inventario.app.ui.battery.BatteryStockAvailabilitySection
import com.inventario.app.ui.theme.AppScreenBackground
import com.inventario.app.ui.theme.BrandAppTopBar
import com.inventario.app.ui.theme.StatusPill
import com.inventario.app.ui.theme.screenHorizontalPadding
import com.inventario.app.ui.theme.screenVerticalPadding

@Composable
fun OilFilterFinderScreen(
    viewModel: OilFilterFinderViewModel,
    subtitle: String,
    onBack: () -> Unit,
    onLogout: () -> Unit
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            BrandAppTopBar(
                subtitle = subtitle,
                onRefreshBcv = {},
                onLogout = onLogout,
                showBack = true,
                onBack = onBack
            )
        }
    ) { padding ->
        AppScreenBackground(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = screenHorizontalPadding(), vertical = screenVerticalPadding())
            ) {
                OilFilterTitle()
                Spacer(Modifier.height(16.dp))

                when {
                    state.loading -> LoadingState("Cargando catálogo de filtros…")
                    state.error != null -> ErrorState(
                        message = state.error.orEmpty(),
                        onRetry = viewModel::load
                    )
                    else -> {
                        SearchCard(
                            query = state.query,
                            onQueryChange = viewModel::onQueryChange,
                            onSearch = viewModel::search,
                            onClear = viewModel::clear
                        )
                        Spacer(Modifier.height(12.dp))
                        when {
                            state.searching -> LoadingState("Buscando filtro…")
                            state.hasSearched && state.results.isEmpty() && state.extraStock.isEmpty() ->
                                EmptyResults(state.query)
                            state.results.isNotEmpty() || state.extraStock.isNotEmpty() -> ResultsList(
                                results = state.results,
                                extraStock = state.extraStock,
                                bcvRate = state.bcvRate
                            )
                            else -> HintText(state.catalogCount)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OilFilterTitle() {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Validador Filtro",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
            ) {
                Text(
                    text = "Aceite",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Escribe el modelo del vehículo para ver el filtro WEB recomendado.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SearchCard(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.OilBarrel,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Buscar por modelo",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Modelo del vehículo") },
                placeholder = { Text("Ej: Corolla, Aveo, CS35, Rio…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = onClear) {
                            Icon(Icons.Default.Close, contentDescription = "Limpiar búsqueda")
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                shape = RoundedCornerShape(14.dp)
            )
        }
    }
}

@Composable
private fun ResultsList(
    results: List<OilFilterRecommendation>,
    extraStock: List<Product>,
    bcvRate: Double?
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        if (results.isNotEmpty()) {
            item {
                Text(
                    text = if (results.size == 1) {
                        "Filtro recomendado"
                    } else {
                        "${results.size} coincidencias"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            items(results, key = { it.entry.id }) { recommendation ->
                OilFilterResultCard(recommendation = recommendation, bcvRate = bcvRate)
            }
        }
        if (extraStock.isNotEmpty()) {
            item {
                Text(
                    text = "También en inventario",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            items(extraStock, key = { "stock-${it.id}" }) { product ->
                StockProductCard(product = product, bcvRate = bcvRate)
            }
        }
    }
}

@Composable
private fun OilFilterResultCard(
    recommendation: OilFilterRecommendation,
    bcvRate: Double?
) {
    val entry = recommendation.entry
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = entry.vehicleLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            if (entry.detailLabel.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = entry.detailLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = entry.filtroCodigo,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (entry.tipoFiltro.isNotBlank()) {
                    StatusPill(text = entry.tipoFiltro, color = MaterialTheme.colorScheme.primary)
                }
                if (entry.filtroRol.isNotBlank()) {
                    StatusPill(
                        text = "Aceite ${entry.filtroRol}",
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
            if (entry.aceitesRecomendados.isNotEmpty()) {
                InfoBlock(title = "Tipos de aceite recomendados", values = entry.aceitesRecomendados)
            }
            if (entry.alternativas.isNotEmpty()) {
                InfoBlock(title = "Variantes alternativas", values = entry.alternativas)
            }
            if (entry.equivalencias.isNotEmpty()) {
                InfoBlock(title = "Equivalencias", values = entry.equivalencias)
            }
            if (entry.observaciones.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = entry.observaciones,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            BatteryStockAvailabilitySection(
                stockMatch = recommendation.stockMatch,
                bcvRate = bcvRate
            )
            if (recommendation.otherStockFilters.isNotEmpty()) {
                InfoBlock(
                    title = "Otras coincidencias en stock",
                    values = recommendation.otherStockFilters.map { product ->
                        "${product.description} (${formatQty(product.quantity)} ${product.unit})"
                    }
                )
            }
            if (recommendation.oilStock.isNotEmpty()) {
                InfoBlock(
                    title = "Aceite en inventario",
                    values = recommendation.oilStock.map { product ->
                        "${product.description} (${formatQty(product.quantity)} ${product.unit})"
                    }
                )
            }
        }
    }
}

@Composable
private fun StockProductCard(product: Product, bcvRate: Double?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Disponible en inventario",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            BatteryStockAvailabilitySection(
                stockMatch = BatteryStockMatch(
                    inStock = product.quantity > 0,
                    product = product
                ),
                bcvRate = bcvRate
            )
        }
    }
}

private fun formatQty(value: Double): String {
    val rounded = if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
    return rounded
}

@Composable
private fun InfoBlock(title: String, values: List<String>) {
    Spacer(Modifier.height(10.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = values.joinToString("  ·  "),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium
    )
}

@Composable
private fun HintText(catalogCount: Int) {
    Text(
        text = "Catálogo local: $catalogCount aplicaciones. La búsqueda funciona sin internet.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun EmptyResults(query: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Sin coincidencias",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "No encontramos un filtro de aceite para \"$query\". Prueba con marca y modelo (ej: Chevrolet Aveo).",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun LoadingState(message: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onRetry) {
            Text("Reintentar")
        }
    }
}
