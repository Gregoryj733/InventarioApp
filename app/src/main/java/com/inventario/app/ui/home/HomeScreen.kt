package com.inventario.app.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.filled.AddShoppingCart
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.inventario.app.ui.theme.isCompactWidth
import com.inventario.app.ui.theme.isVeryCompactWidth
import com.inventario.app.ui.theme.screenHorizontalPadding
import com.inventario.app.ui.theme.screenVerticalPadding
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inventario.app.data.entity.Product
import com.inventario.app.data.entity.UserRole
import com.inventario.app.data.order.OrderLine
import com.inventario.app.ui.theme.AccentSectionCard
import com.inventario.app.ui.theme.AppScreenBackground
import com.inventario.app.ui.theme.BrandAppTopBar
import com.inventario.app.ui.theme.BrandSuccess
import com.inventario.app.ui.theme.BrandWarning
import com.inventario.app.ui.theme.ReportDivider
import com.inventario.app.ui.theme.ReportHeader
import com.inventario.app.ui.theme.ReportKeyValueRow
import com.inventario.app.ui.theme.ConfirmedOrdersBanner
import com.inventario.app.ui.theme.ReportMetaChip
import com.inventario.app.ui.theme.ReportTotalBanner
import com.inventario.app.ui.theme.StatusPill
import com.inventario.app.ui.theme.confirmedOrdersLabel
import com.inventario.app.ui.theme.WhatsAppGreen
import com.inventario.app.ui.theme.WhatsAppGreenDark
import com.inventario.app.util.WhatsAppNotifier

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    subtitle: String,
    onBack: () -> Unit,
    onLogout: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val displayProducts = viewModel.displayProducts()
    val context = LocalContext.current
    val horizontalPad = screenHorizontalPadding()
    val verticalPad = screenVerticalPadding()
    val compact = isCompactWidth()
    val topBarSubtitle = subtitle
    val orderProductIds = remember(state.orderLines) {
        state.orderLines.mapTo(HashSet()) { it.productId }
    }
    val pickExcel = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) viewModel.importExcel(uri)
    }
    val launchExcelImport: () -> Unit = {
        pickExcel.launch(
            arrayOf(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-excel",
                "application/octet-stream"
            )
        )
    }

    if (state.showCloudConfigDialog) {
        CloudConfigDialog(
            url = state.cloudConfigUrl,
            apiKey = state.cloudConfigApiKey,
            message = state.cloudConfigMessage,
            onUrlChange = viewModel::onCloudConfigUrlChange,
            onApiKeyChange = viewModel::onCloudConfigApiKeyChange,
            onDismiss = viewModel::dismissCloudConfigDialog,
            onSave = viewModel::saveCloudConfig
        )
    }

    if (state.importAlert != null) {
        ImportAlertDialog(
            alert = state.importAlert!!,
            onDismiss = viewModel::dismissImportAlert
        )
    }

    if (state.showReceipt) {
        OrderReceiptDialog(
            state = state,
            viewModel = viewModel,
            onDismiss = viewModel::dismissReceipt,
            onConfirm = {
                viewModel.confirmOrder { message ->
                    WhatsAppNotifier.shareToGroupChooser(context, message)
                }
            }
        )
    }

    Scaffold(
        topBar = {
            BrandAppTopBar(
                subtitle = topBarSubtitle,
                onRefreshBcv = viewModel::refreshBcv,
                onLogout = { viewModel.logout(onLogout) },
                showImportInventory = state.role == UserRole.ADMIN,
                onImportInventory = launchExcelImport,
                importEnabled = !state.importing,
                bcvRefreshing = state.bcvRefreshing,
                showBack = true,
                onBack = onBack
            )
        }
    ) { padding ->
        AppScreenBackground(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .padding(horizontal = horizontalPad, vertical = verticalPad),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item(key = "info_header") {
                    InfoHeader(state = state)
                }

                if (state.role == UserRole.ADMIN && state.productCount == 0) {
                    item(key = "admin_prompt") {
                        AdminInventoryPromptCard(
                            importing = state.importing,
                            onImport = launchExcelImport
                        )
                    }
                }

                item(key = "search") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SearchField(
                            query = state.query,
                            onQueryChange = viewModel::onQueryChange,
                            onSearch = viewModel::runSearch,
                            onClear = viewModel::clearSearch
                        )
                        ConfirmedOrdersBanner(
                            count = state.confirmedOrdersToday,
                            onReset = viewModel::resetTodayOrders,
                            resetting = state.resettingOrders
                        )
                    }
                }

                if (state.suggestions.isNotEmpty()) {
                    item(key = "suggestions") {
                        SuggestionsCard(
                            suggestions = state.suggestions,
                            onSelect = viewModel::selectSuggestion
                        )
                    }
                }

                if (state.role == UserRole.ADMIN) {
                    item(key = "import_btn") {
                        Button(
                            onClick = launchExcelImport,
                            enabled = !state.importing,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.CloudUpload, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (state.importing) "Importando…"
                                else if (compact) "Cargar inventario"
                                else "Cargar inventario (Excel)"
                            )
                        }
                    }
                    if (viewModel.needsCloudConfigButton()) {
                        item(key = "cloud_config_btn") {
                            OutlinedButton(
                                onClick = viewModel::openCloudConfigDialog,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.CloudSync, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(if (compact) "Config. sincronización" else "Configurar sincronización")
                            }
                        }
                    }
                }

                if (state.lastWhatsAppMessage != null) {
                    item(key = "whatsapp_followup") {
                        WhatsAppFollowUpCard(
                            successText = state.orderSuccessMessage,
                            compact = compact,
                            onShareToGroup = {
                                WhatsAppNotifier.shareToGroupChooser(context, state.lastWhatsAppMessage!!)
                            },
                            onDismiss = viewModel::clearWhatsAppFollowUp
                        )
                    }
                } else if (state.orderSuccessMessage != null) {
                    item(key = "order_success") {
                        Text(
                            state.orderSuccessMessage!!,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                if (state.error != null) {
                    item(key = "error") {
                        Text(state.error!!, color = MaterialTheme.colorScheme.error)
                    }
                }

                if (state.selectedProduct != null) {
                    item(key = "selected_product") {
                        SelectedProductPanel(
                            viewModel = viewModel,
                            state = state,
                            onClear = viewModel::clearSelection,
                            onAddToOrder = viewModel::addToOrder
                        )
                    }
                }

                if (state.orderLines.isNotEmpty()) {
                    item(key = "order_summary") {
                        OrderSummaryCard(
                            state = state,
                            viewModel = viewModel,
                            onConfirm = viewModel::showOrderReceipt,
                            onClear = viewModel::clearOrder
                        )
                    }
                }

                when {
                    state.searching -> {
                        item(key = "loading") {
                            BoxLoading()
                        }
                    }
                    displayProducts.isEmpty() && state.query.trim().isNotEmpty() -> {
                        item(key = "no_results") {
                            Text(
                                "Sin resultados para \"${state.query}\"",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                    displayProducts.isEmpty() -> {
                        item(key = "empty_inventory") {
                            Text(
                                when {
                                    state.productCount == 0 && state.role == UserRole.ADMIN ->
                                        "Aún no hay inventario. Usa «Cargar inventario» en la barra superior o el botón de abajo."
                                    state.productCount == 0 ->
                                        "Aún no hay inventario. Pide al administrador que lo cargue."
                                    else ->
                                        "Escribe para buscar. Toca un producto y agrégalo al pedido."
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                    else -> {
                        if (state.query.trim().isEmpty() && state.allProducts.isNotEmpty()) {
                            item(key = "inventory_label") {
                                Text(
                                    "Inventario (${state.allProducts.size} productos). Toca para agregar al pedido.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 2.dp)
                                )
                            }
                        }
                        items(displayProducts, key = { it.id }) { product ->
                            val inOrder = product.id in orderProductIds
                            val selected = state.selectedProduct?.id == product.id
                            ProductRow(
                                product = product,
                                selected = selected,
                                inOrder = inOrder,
                                compact = compact,
                                priceLabel = viewModel.formatPrice(product.price),
                                qtyLabel = "${viewModel.formatQty(product.quantity)} ${product.unit}",
                                bsLabel = viewModel.bsEquivalent(product.price),
                                onClick = { viewModel.selectProduct(product) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminInventoryPromptCard(
    importing: Boolean,
    onImport: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Sin inventario cargado",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Como administrador, carga el archivo product.xlsx para ver y gestionar el inventario en la app y en la nube.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onImport,
                enabled = !importing,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.CloudUpload, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (importing) "Importando…" else "Cargar inventario (Excel)")
            }
        }
    }
}

@Composable
private fun CloudConfigDialog(
    url: String,
    apiKey: String,
    message: String?,
    onUrlChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Configurar sincronización", fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                Text(
                    "Opción nube (Render): https://inventario-sync-totalcare.onrender.com\n" +
                        "Opción local: http://192.168.1.10:8787\n" +
                        "Clave API: la misma que configuraste en el servidor",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = onUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("URL del servidor") },
                    placeholder = { Text("https://inventario-sync-totalcare.onrender.com") },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = onApiKeyChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Clave API (opcional)") },
                    placeholder = { Text("inventario-sync-key") },
                    singleLine = true
                )
                if (message != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = onSave, shape = RoundedCornerShape(12.dp)) {
                Text("Guardar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

@Composable
private fun ImportAlertDialog(
    alert: ImportAlert,
    onDismiss: () -> Unit
) {
    val iconTint = if (alert.isSuccess) BrandSuccess else MaterialTheme.colorScheme.error
    val icon = if (alert.isSuccess) Icons.Default.CheckCircle else Icons.Default.ErrorOutline

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint
            )
        },
        title = {
            Text(
                text = alert.title,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(alert.message)
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Entendido")
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InfoHeader(state: HomeUiState) {
    AccentSectionCard(
        title = "Resumen del día",
        titleTrailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ReportMetaChip(icon = "📅", text = state.currentDate)
                if (state.bcvRefreshing) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
        }
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ReportMetaChip(
                icon = "💱",
                text = state.bcvLabel
            )
            ReportMetaChip(
                icon = "🧾",
                text = confirmedOrdersLabel(state.confirmedOrdersToday),
                highlight = true
            )
            StatusPill(
                text = "${state.productCount} productos",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            StatusPill(
                text = if (state.role == UserRole.ADMIN) "Administrador" else "Consulta",
                color = MaterialTheme.colorScheme.secondary
            )
            StatusPill(
                text = state.cloudSyncLabel,
                color = when {
                    state.cloudSyncLabel.contains("sincronizado") -> BrandSuccess
                    state.cloudSyncLabel.contains("subida pendiente") ||
                        state.cloudSyncLabel.contains("servidor iniciando") -> BrandWarning
                    state.cloudSyncLabel.contains("error") ||
                        state.cloudSyncLabel.contains("denegado") ||
                        state.cloudSyncLabel.contains("no configurad") ||
                        state.cloudSyncLabel.contains("no encontrado") -> MaterialTheme.colorScheme.error
                    state.cloudSyncLabel.contains("sin conexión") -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        if (state.cloudSyncDetail != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = state.cloudSyncDetail,
                style = MaterialTheme.typography.bodySmall,
                color = if (
                    state.cloudSyncLabel.contains("pendiente", ignoreCase = true) ||
                    state.cloudSyncLabel.contains("iniciando", ignoreCase = true)
                ) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Buscar producto") },
        placeholder = { Text("Ej: aceite, faro, kia…") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Limpiar búsqueda"
                    )
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        shape = RoundedCornerShape(14.dp)
    )
}

@Composable
private fun SuggestionsCard(
    suggestions: List<String>,
    onSelect: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column {
            suggestions.forEach { suggestion ->
                Text(
                    text = suggestion,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(suggestion) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}

@Composable
private fun SelectedProductPanel(
    viewModel: HomeViewModel,
    state: HomeUiState,
    onClear: () -> Unit,
    onAddToOrder: () -> Unit
) {
    val product = state.selectedProduct ?: return
    val qty = viewModel.selectedQtyValue()
    val totalUsd = viewModel.lineTotalUsd()
    val totalBs = viewModel.lineTotalBs()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(3.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Producto seleccionado",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                IconButton(onClick = onClear) {
                    Icon(Icons.Default.Close, contentDescription = "Cerrar")
                }
            }

            Text(
                text = product.description,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text("Precio unitario: ${viewModel.formatPrice(product.price)}")
            Text(
                text = "Stock: ${viewModel.formatQty(product.quantity)} ${product.unit}",
                color = MaterialTheme.colorScheme.primary
            )
            viewModel.bsEquivalent(product.price)?.let { unitBs ->
                Text(
                    text = "Equiv. unitario BCV: $unitBs",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = state.selectedQtyText,
                onValueChange = viewModel::onSelectedQtyChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Cantidad (${product.unit})") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done
                ),
                shape = RoundedCornerShape(12.dp),
                isError = state.qtyWarning != null
            )
            if (state.qtyWarning != null) {
                Text(
                    text = state.qtyWarning!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "Total: ${if (qty > 0) viewModel.formatPrice(totalUsd) else "—"}" +
                    if (totalBs != null && qty > 0) " · Bs ${viewModel.formatMoney(totalBs)}" else "",
                fontWeight = FontWeight.SemiBold
            )

            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onAddToOrder,
                enabled = viewModel.canAddToOrder(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.AddShoppingCart, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Agregar al pedido")
            }

            viewModel.casheaSimulation()?.let { simulation ->
                CasheaSimulationPanel(
                    simulation = simulation,
                    formatPrice = viewModel::formatPrice,
                    formatMoney = viewModel::formatMoney
                )
            }
        }
    }
}

@Composable
private fun OrderSummaryCard(
    state: HomeUiState,
    viewModel: HomeViewModel,
    onConfirm: () -> Unit,
    onClear: () -> Unit
) {
    val onPrimary = MaterialTheme.colorScheme.onPrimary

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Pedido actual (${state.orderLines.size} ítems)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = onPrimary,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onClear) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Vaciar pedido",
                        tint = onPrimary.copy(alpha = 0.92f)
                    )
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    state.orderLines.forEach { line ->
                        ReportKeyValueRow(
                            label = "${viewModel.formatQty(line.quantity)} ${line.unit} · ${line.description}",
                            value = viewModel.formatPrice(line.totalUsd),
                            valueColor = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    ReportDivider(label = "Total")
                    Spacer(Modifier.height(6.dp))

                    val totalBs = viewModel.orderTotalBs()
                    ReportTotalBanner(
                        label = "Total del pedido",
                        usd = viewModel.formatPrice(viewModel.orderTotalUsd()),
                        bs = totalBs?.let { "Bs ${viewModel.formatMoney(it)}" }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text("Confirmar pedido y ver boleta", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun OrderReceiptDialog(
    state: HomeUiState,
    viewModel: HomeViewModel,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val totalUsd = viewModel.orderTotalUsd()
    val totalBs = viewModel.orderTotalBs()
    val compact = isCompactWidth()

    AlertDialog(
        onDismissRequest = { if (!state.orderProcessing) onDismiss() },
        title = null,
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                ReportHeader(
                    title = "Boleta de pedido",
                    subtitle = "Total Care Automotriz"
                )
                Spacer(Modifier.height(12.dp))
                if (compact) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ReportMetaChip(icon = "📅", text = state.currentDate, modifier = Modifier.fillMaxWidth())
                        ReportMetaChip(icon = "👤", text = state.username, modifier = Modifier.fillMaxWidth())
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ReportMetaChip(icon = "📅", text = state.currentDate, modifier = Modifier.weight(1f))
                        ReportMetaChip(icon = "👤", text = state.username, modifier = Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = state.bcvLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(14.dp))
                ReportDivider(label = "Detalle")

                state.orderLines.forEachIndexed { index, line ->
                    Spacer(Modifier.height(8.dp))
                    ReceiptLine(
                        index = index + 1,
                        line = line,
                        viewModel = viewModel,
                        bcvRate = state.bcvRate
                    )
                }

                Spacer(Modifier.height(12.dp))
                ReportDivider(label = "Totales")
                Spacer(Modifier.height(8.dp))
                ReportTotalBanner(
                    label = "Total del pedido",
                    usd = viewModel.formatPrice(totalUsd),
                    bs = totalBs?.let { "Bs ${viewModel.formatMoney(it)}" }
                )

                Spacer(Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Al confirmar:",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "• Se descuenta el stock del inventario\n" +
                                "• Se abre WhatsApp para enviar al grupo «${WhatsAppNotifier.GROUP_NAME}»",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp
                        )
                    }
                }

                if (state.orderProcessing) {
                    Spacer(Modifier.height(14.dp))
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !state.orderProcessing,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Confirmar pedido", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                enabled = !state.orderProcessing,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Cancelar")
            }
        }
    )
}

@Composable
private fun WhatsAppFollowUpCard(
    successText: String?,
    compact: Boolean,
    onShareToGroup: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = WhatsAppGreen.copy(alpha = 0.08f)
        ),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = successText ?: "Pedido registrado",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = WhatsAppGreenDark,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Cerrar")
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "Enviar boleta por WhatsApp",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Para el grupo \"${WhatsAppNotifier.GROUP_NAME}\":",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "1. Pulsa «Reenviar al grupo» abajo.\n" +
                    "2. En WhatsApp, busca y elige el grupo «${WhatsAppNotifier.GROUP_NAME}».\n" +
                    "3. Confirma el envío del mensaje.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onShareToGroup,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = WhatsAppGreen,
                    contentColor = androidx.compose.ui.graphics.Color.White
                )
            ) {
                Icon(Icons.Default.Share, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (compact) "Reenviar al grupo"
                    else "Reenviar al grupo Control Interno"
                )
            }
        }
    }
}

@Composable
private fun ReceiptLine(
    index: Int,
    line: OrderLine,
    viewModel: HomeViewModel,
    bcvRate: Double?
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "$index. ${line.description}",
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${viewModel.formatQty(line.quantity)} ${line.unit} × ${viewModel.formatPrice(line.unitPriceUsd)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(viewModel.formatPrice(line.totalUsd), style = MaterialTheme.typography.bodySmall)
        }
        if (bcvRate != null) {
            Text(
                text = "Bs ${viewModel.formatMoney(line.totalUsd * bcvRate)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

@Composable
private fun BoxLoading() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ProductRow(
    product: Product,
    selected: Boolean,
    inOrder: Boolean,
    compact: Boolean,
    priceLabel: String,
    qtyLabel: String,
    bsLabel: String?,
    onClick: () -> Unit
) {
    val borderColor = when {
        selected -> MaterialTheme.colorScheme.secondary
        inOrder -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        else -> Color.Transparent
    }
    val borderWidth = if (selected) 2.dp else if (inOrder) 1.dp else 0.dp

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (borderWidth > 0.dp) {
                    Modifier.border(borderWidth, borderColor, RoundedCornerShape(16.dp))
                } else Modifier
            )
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(if (selected) 3.dp else 1.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = product.description,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (inOrder) {
                    StatusPill(text = "En pedido", color = BrandSuccess)
                }
            }
            Spacer(Modifier.height(6.dp))
            if (compact) {
                Text(
                    text = "Precio: $priceLabel",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Stock: $qtyLabel",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Precio: $priceLabel",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Stock: $qtyLabel",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            if (bsLabel != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "BCV: $bsLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
