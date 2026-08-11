package com.inventario.app.ui.batteryfinder

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.inventario.app.data.battery.BatteryRecommendation
import com.inventario.app.ui.battery.BatteryHigherAmperageNote
import com.inventario.app.ui.battery.BatteryStockAvailabilitySection
import com.inventario.app.ui.theme.AppScreenBackground
import com.inventario.app.ui.theme.BrandAppTopBar
import com.inventario.app.ui.theme.screenHorizontalPadding
import com.inventario.app.ui.theme.screenVerticalPadding

private const val BATTERY_FINDER_TITLE = "Validador Batería"
private const val BATTERY_FINDER_BRAND = "Duncan"

@Composable
fun BatteryFinderScreen(
    viewModel: BatteryFinderViewModel,
    subtitle: String,
    onBack: () -> Unit,
    onLogout: () -> Unit
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.onScreenVisible()
    }

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
                BatteryFinderScreenTitle()
                Spacer(Modifier.height(16.dp))

                when {
                    state.loading -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = "Cargando catálogo de baterías…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    state.error != null -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = state.error.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(12.dp))
                            TextButton(onClick = viewModel::load) {
                                Text("Reintentar")
                            }
                        }
                    }
                    else -> {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            item {
                                BatteryFinderSelectorsCard(
                                    state = state,
                                    onMarcaSelected = viewModel::onMarcaSelected,
                                    onModeloSelected = viewModel::onModeloSelected,
                                    onAnioSelected = viewModel::onAnioSelected,
                                    onClear = viewModel::clearSelection
                                )
                            }
                            if (state.hasResult) {
                                item {
                                    BatteryFinderResultCard(
                                        marca = state.selectedMarca,
                                        modelo = state.selectedModelo,
                                        anio = state.selectedAnio,
                                        recommendations = state.recommendations,
                                        bcvRate = state.bcvRate
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BatteryFinderScreenTitle() {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = BATTERY_FINDER_TITLE,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
            ) {
                Text(
                    text = BATTERY_FINDER_BRAND,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "¡Encuentra fácilmente la batería que tu vehículo necesita!",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BatteryFinderSelectorsCard(
    state: BatteryFinderUiState,
    onMarcaSelected: (String) -> Unit,
    onModeloSelected: (String) -> Unit,
    onAnioSelected: (String) -> Unit,
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
                    imageVector = Icons.Default.DirectionsCar,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Encontrar batería",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(14.dp))

            BatteryFinderDropdown(
                label = "Marca",
                options = state.marcas,
                selected = state.selectedMarca,
                enabled = true,
                onSelected = onMarcaSelected,
                displayOption = { it.uppercase() },
                menuKey = "marca"
            )
            Spacer(Modifier.height(10.dp))
            BatteryFinderDropdown(
                label = "Modelo",
                options = state.modelos,
                selected = state.selectedModelo,
                enabled = state.selectedMarca != null,
                onSelected = onModeloSelected,
                displayOption = { it.uppercase() },
                menuKey = "modelo-${state.selectedMarca.orEmpty()}"
            )
            Spacer(Modifier.height(10.dp))
            BatteryFinderDropdown(
                label = "Año",
                options = state.anios,
                selected = state.selectedAnio,
                enabled = state.selectedModelo != null,
                onSelected = onAnioSelected,
                displayOption = { it },
                menuKey = "anio-${state.selectedMarca.orEmpty()}-${state.selectedModelo.orEmpty()}"
            )

            if (state.selectedMarca != null) {
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = onClear) {
                    Text("Limpiar selección")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BatteryFinderDropdown(
    label: String,
    options: List<String>,
    selected: String?,
    enabled: Boolean,
    onSelected: (String) -> Unit,
    displayOption: (String) -> String,
    menuKey: String
) {
    var expanded by remember(menuKey) { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = it }
    ) {
        OutlinedTextField(
            value = selected?.let(displayOption) ?: "Elige una opción",
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && enabled) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled)
        )
        ExposedDropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(displayOption(option)) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun BatteryFinderResultCard(
    marca: String?,
    modelo: String?,
    anio: String?,
    recommendations: List<BatteryRecommendation>,
    bcvRate: Double?
) {
    val vehicleLabel = listOfNotNull(
        marca?.uppercase(),
        modelo?.uppercase(),
        anio
    ).joinToString(" · ")

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.BatteryChargingFull,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (recommendations.isEmpty()) "Sin coincidencias" else "Batería recomendada",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(10.dp))
            if (vehicleLabel.isNotBlank()) {
                Text(
                    text = vehicleLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
            }
            if (recommendations.isEmpty()) {
                Text(
                    text = "No encontramos una batería para esta combinación. Contáctanos para asesorarte.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            } else {
                recommendations.forEach { recommendation ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = recommendation.code,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    BatteryStockAvailabilitySection(
                        stockMatch = recommendation.stockMatch,
                        bcvRate = bcvRate
                    )
                }
                BatteryHigherAmperageNote()
            }
        }
    }
}
