package com.inventario.app.ui.powermaxx

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inventario.app.ui.battery.BatteryHigherAmperageNote
import com.inventario.app.ui.battery.BatteryStockAvailabilitySection
import com.inventario.app.ui.theme.AppScreenBackground
import com.inventario.app.ui.theme.BrandAppTopBar
import com.inventario.app.ui.theme.screenHorizontalPadding
import com.inventario.app.ui.theme.screenVerticalPadding

private val AcPowerYellow = Color(0xFFFFDD00)
private val AcPowerBlack = Color(0xFF0F0F0F)

@Composable
fun PowerMaxxBatteryScreen(
    viewModel: PowerMaxxBatteryViewModel,
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
                PowerMaxxTitle()
                Spacer(Modifier.height(16.dp))

                when {
                    state.loading -> LoadingContent(message = "Cargando catálogo AC Power…")
                    state.error != null && state.marcas.isEmpty() -> ErrorContent(
                        message = state.error.orEmpty(),
                        onRetry = viewModel::loadMarcas
                    )
                    else -> {
                        PowerMaxxSearchCard(
                            state = state,
                            onModeSelected = viewModel::setSearchMode,
                            onMarcaSelected = viewModel::onMarcaSelected,
                            onModeloSelected = viewModel::onModeloSelected,
                            onBatteryCodeChanged = viewModel::onBatteryCodeChanged,
                            onSearch = viewModel::search,
                            onClear = viewModel::clearVehicleSelection
                        )

                        if (state.searching) {
                            Spacer(Modifier.height(20.dp))
                            LoadingContent(message = "Buscando batería…")
                        } else if (state.hasSearched) {
                            Spacer(Modifier.height(16.dp))
                            PowerMaxxResultsSection(
                                state = state,
                                onSearch = viewModel::search
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PowerMaxxTitle() {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "ENCUENTRA TU BATERÍA",
            style = MaterialTheme.typography.headlineSmall.copy(
                fontStyle = FontStyle.Italic,
                letterSpacing = 0.5.sp
            ),
            fontWeight = FontWeight.Bold,
            color = AcPowerBlack
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "AC POWER",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                color = AcPowerBlack
            )
            Icon(
                imageVector = Icons.Default.BatteryChargingFull,
                contentDescription = null,
                tint = AcPowerBlack,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Validador oficial — misma lógica que acpowervzla.com",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PowerMaxxSearchCard(
    state: PowerMaxxBatteryUiState,
    onModeSelected: (PowerMaxxSearchMode) -> Unit,
    onMarcaSelected: (String) -> Unit,
    onModeloSelected: (String) -> Unit,
    onBatteryCodeChanged: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(4.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            PowerMaxxTabRow(
                selectedMode = state.searchMode,
                onModeSelected = onModeSelected
            )
            Spacer(Modifier.height(16.dp))

            when (state.searchMode) {
                PowerMaxxSearchMode.VEHICLE -> {
                    PowerMaxxDropdown(
                        label = "Marca",
                        options = state.marcas,
                        selected = state.selectedMarca,
                        enabled = true,
                        onSelected = onMarcaSelected,
                        menuKey = "marca"
                    )
                    Spacer(Modifier.height(10.dp))
                    PowerMaxxDropdown(
                        label = "Modelo",
                        options = state.modelos,
                        selected = state.selectedModelo,
                        enabled = state.selectedMarca != null && !state.loadingModelos,
                        loading = state.loadingModelos,
                        onSelected = onModeloSelected,
                        menuKey = "modelo-${state.selectedMarca.orEmpty()}"
                    )
                    if (state.selectedMarca != null) {
                        Spacer(Modifier.height(6.dp))
                        TextButton(onClick = onClear) {
                            Text("Limpiar selección")
                        }
                    }
                }
                PowerMaxxSearchMode.BATTERY_CODE -> {
                    OutlinedTextField(
                        value = state.batteryCodeQuery,
                        onValueChange = onBatteryCodeChanged,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Código de batería") },
                        placeholder = { Text("Ej: 24R-1100") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            imeAction = ImeAction.Search
                        ),
                        keyboardActions = KeyboardActions(onSearch = { onSearch() })
                    )
                }
            }

            if (state.error != null && state.marcas.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = state.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onSearch,
                enabled = !state.searching,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AcPowerBlack,
                    contentColor = Color.White,
                    disabledContainerColor = AcPowerBlack.copy(alpha = 0.4f)
                )
            ) {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("SEARCH", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }
    }
}

@Composable
private fun PowerMaxxTabRow(
    selectedMode: PowerMaxxSearchMode,
    onModeSelected: (PowerMaxxSearchMode) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        PowerMaxxTab(
            label = "VEHÍCULO / INDUSTRIA",
            selected = selectedMode == PowerMaxxSearchMode.VEHICLE,
            onClick = { onModeSelected(PowerMaxxSearchMode.VEHICLE) },
            modifier = Modifier.weight(1f)
        )
        PowerMaxxTab(
            label = "CÓDIGO DE BATERÍA",
            selected = selectedMode == PowerMaxxSearchMode.BATTERY_CODE,
            onClick = { onModeSelected(PowerMaxxSearchMode.BATTERY_CODE) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun PowerMaxxTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) AcPowerBlack else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 14.sp
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .height(if (selected) 3.dp else 1.dp)
                .background(if (selected) AcPowerYellow else Color.LightGray.copy(alpha = 0.5f))
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PowerMaxxDropdown(
    label: String,
    options: List<String>,
    selected: String?,
    enabled: Boolean,
    loading: Boolean = false,
    onSelected: (String) -> Unit,
    menuKey: String
) {
    var expanded by remember(menuKey) { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded && enabled && !loading,
        onExpandedChange = { if (enabled && !loading) expanded = it }
    ) {
        OutlinedTextField(
            value = when {
                loading -> "Cargando…"
                selected != null -> selected
                else -> label
            },
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = AcPowerBlack
                    )
                } else {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && enabled)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled && !loading)
        )
        ExposedDropdownMenu(
            expanded = expanded && enabled && !loading,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
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
private fun PowerMaxxResultsSection(
    state: PowerMaxxBatteryUiState,
    onSearch: () -> Unit
) {
    if (state.results.isEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Sin coincidencias",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "No encontramos una batería AC Power para esta búsqueda.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onSearch) {
                    Text("Reintentar")
                }
            }
        }
        return
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            val contextLabel = when (state.searchMode) {
                PowerMaxxSearchMode.VEHICLE -> listOfNotNull(
                    state.selectedMarca,
                    state.selectedModelo
                ).joinToString(" · ")
                PowerMaxxSearchMode.BATTERY_CODE -> "Código: ${state.batteryCodeQuery}"
            }
            Text(
                text = if (state.results.size == 1) "Batería validada" else "${state.results.size} baterías encontradas",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            if (contextLabel.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = contextLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        items(state.results, key = { it.product.code }) { result ->
            PowerMaxxProductCard(result = result, bcvRate = state.bcvRate)
        }
        item {
            BatteryHigherAmperageNote()
        }
    }
}

@Composable
private fun PowerMaxxProductCard(
    result: PowerMaxxBatteryResult,
    bcvRate: Double?
) {
    val product = result.product
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = AcPowerYellow.copy(alpha = 0.18f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = AcPowerBlack,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = product.code.ifBlank { "—" },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color = AcPowerBlack
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = product.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            product.line?.let { line ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (product.features.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                product.features.forEach { feature ->
                    Text(
                        text = "• $feature",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
            BatteryStockAvailabilitySection(
                stockMatch = result.stockMatch,
                bcvRate = bcvRate
            )
        }
    }
}

@Composable
private fun LoadingContent(message: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = AcPowerBlack)
        Spacer(Modifier.height(12.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
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
