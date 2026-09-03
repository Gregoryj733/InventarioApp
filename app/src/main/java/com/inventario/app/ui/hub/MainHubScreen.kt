package com.inventario.app.ui.hub

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.OilBarrel
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inventario.app.ui.theme.BranchSelector
import com.inventario.app.ui.theme.screenHorizontalPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import com.inventario.app.data.branch.BranchConfig
import com.inventario.app.data.entity.CashClosingAlertType
import com.inventario.app.data.entity.CLOSING_EXCEL_REMINDER_MESSAGE
import com.inventario.app.data.entity.UserRole
import com.inventario.app.data.entity.displayLabel
import com.inventario.app.ui.cashclosing.ClosingExcelReminderBanner
import com.inventario.app.ui.theme.AppScreenBackground
import com.inventario.app.ui.theme.HubDailySnapshotCard
import com.inventario.app.ui.theme.BrandAppTopBar
import com.inventario.app.ui.theme.BrandWarning
import com.inventario.app.ui.theme.screenHorizontalPadding
import com.inventario.app.ui.theme.screenVerticalPadding
import kotlinx.coroutines.launch

private val HubCardHeight = 176.dp

enum class HubDestination {
    INVENTORY,
    COUPON_ACTIVATE,
    CASH_CLOSING,
    REPORTS,
    USERS,
    BATTERY_FINDER,
    POWER_MAXX_BATTERY,
    OIL_FILTER_FINDER
}

data class HubMenuItem(
    val destination: HubDestination,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val showAlertBell: Boolean = false
)

@Composable
fun MainHubScreen(
    username: String,
    role: UserRole,
    activeBranchLabel: String = "",
    canSwitchBranch: Boolean = false,
    availableBranches: List<BranchConfig> = emptyList(),
    showBranchSwitchDialog: Boolean = false,
    branchSwitchLoading: Boolean = false,
    branchSwitchError: String? = null,
    pendingReauthBranchId: String? = null,
    pendingBranchSwitchId: String? = null,
    reauthPassword: String = "",
    activeBranchId: String = "",
    bcvLabel: String,
    bcvRefreshing: Boolean,
    showBranchKpis: Boolean = false,
    branchSalesKpis: List<com.inventario.app.data.repository.BranchDailySalesKpi> = emptyList(),
    branchKpisLoading: Boolean = false,
    cashClosingAlert: CashClosingAlertType? = null,
    pendingReportsCount: Int = 0,
    showClosingExcelReminder: Boolean = false,
    exportingClosingExcel: Boolean = false,
    suggestedClosingExportFileName: () -> String = { "cierre_caja.xlsx" },
    onPrepareClosingExcelExport: suspend () -> Boolean = { false },
    onExportClosingExcelToUri: suspend (Uri) -> Result<Unit> = { Result.failure(IllegalStateException()) },
    onFinishClosingExcelExport: (Boolean, String?) -> Unit = { _, _ -> },
    onNavigate: (HubDestination) -> Unit,
    onRefreshBcv: () -> Unit,
    onLogout: () -> Unit,
    showBcvAdminDialog: Boolean = false,
    bcvAdminRateText: String = "",
    bcvAdminSaving: Boolean = false,
    bcvAdminError: String? = null,
    bcvManualOverride: Boolean = false,
    onOpenBcvAdminDialog: (() -> Unit)? = null,
    onDismissBcvAdminDialog: () -> Unit = {},
    onBcvAdminRateChange: (String) -> Unit = {},
    onSaveManualBcvRate: () -> Unit = {},
    onRestoreAutomaticBcv: () -> Unit = {},
    onOpenBranchSwitch: () -> Unit = {},
    onDismissBranchSwitch: () -> Unit = {},
    onBranchSelected: (String) -> Unit = {},
    onReauthPasswordChange: (String) -> Unit = {},
    onConfirmBranchReauth: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        )
    ) { uri ->
        if (uri == null) {
            onFinishClosingExcelExport(false, "Exportación cancelada.")
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val result = onExportClosingExcelToUri(uri)
            onFinishClosingExcelExport(
                result.isSuccess,
                result.exceptionOrNull()?.message
            )
        }
    }

    val subtitle = buildString {
        append(username)
        append(" · ")
        append(role.displayLabel())
        if (activeBranchLabel.isNotBlank()) {
            append(" · ")
            append(activeBranchLabel)
        }
    }
    val items = buildList {
        add(
            HubMenuItem(
                destination = HubDestination.INVENTORY,
                title = "Inventario",
                subtitle = "Buscar productos y pedidos",
                icon = Icons.Default.Inventory2
            )
        )
        add(
            HubMenuItem(
                destination = HubDestination.COUPON_ACTIVATE,
                title = "Activar cupón",
                subtitle = "1.er escaneo: activar · 2.º: ejecutar en carrito",
                icon = Icons.Default.QrCodeScanner
            )
        )
        add(
            HubMenuItem(
                destination = HubDestination.CASH_CLOSING,
                title = "Cierre de caja",
                subtitle = if (cashClosingAlert == CashClosingAlertType.REJECTED_RESUBMIT) {
                    "Requiere nuevo cierre"
                } else if (cashClosingAlert == CashClosingAlertType.APPROVED_SUCCESS) {
                    "Cierre aprobado hoy"
                } else {
                    "Cuadre diario"
                },
                icon = Icons.Default.PointOfSale,
                showAlertBell = cashClosingAlert != null
            )
        )
        // Visible para todos los perfiles: es una herramienta de consulta de
        // referencia (no afecta inventario, ventas ni cierres), igual que el
        // buscador de baterías de duncan.com.ve en el que se basa.
        add(
            HubMenuItem(
                destination = HubDestination.BATTERY_FINDER,
                title = "Validador\nBatería Duncan",
                subtitle = "Encuentra la batería de tu vehículo",
                icon = Icons.Default.BatteryChargingFull
            )
        )
        add(
            HubMenuItem(
                destination = HubDestination.POWER_MAXX_BATTERY,
                title = "Validador Batería\nPower Maxx",
                subtitle = "Catálogo oficial AC Power",
                icon = Icons.Default.BatteryChargingFull
            )
        )
        add(
            HubMenuItem(
                destination = HubDestination.OIL_FILTER_FINDER,
                title = "Validador\nFiltro Aceite",
                subtitle = "Filtro WEB según el modelo",
                icon = Icons.Default.OilBarrel
            )
        )
        // Consulta no participa en el flujo de aprobación de cierres de caja.
        if (role == UserRole.ADMIN || role == UserRole.SUPERVISOR) {
            add(
                HubMenuItem(
                    destination = HubDestination.REPORTS,
                    title = "Flujo Aprobación",
                    subtitle = if (pendingReportsCount > 0) {
                        "Cierres pendientes de validar"
                    } else {
                        "KPIs y aprobaciones"
                    },
                    icon = Icons.AutoMirrored.Filled.FactCheck,
                    showAlertBell = pendingReportsCount > 0
                )
            )
        }
        if (role == UserRole.ADMIN) {
            add(
                HubMenuItem(
                    destination = HubDestination.USERS,
                    title = "Usuarios",
                    subtitle = "Gestionar supervisores y consulta",
                    icon = Icons.Default.People
                )
            )
        }
    }
    val columns = 2

    if (showBranchSwitchDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!branchSwitchLoading) onDismissBranchSwitch()
            },
            title = {
                Text(
                    text = if (pendingReauthBranchId != null) {
                        "Confirmar acceso"
                    } else {
                        "Cambiar sucursal"
                    },
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (pendingReauthBranchId != null) {
                        val branchLabel = availableBranches
                            .firstOrNull { it.id == pendingReauthBranchId }
                            ?.chipLabel
                            .orEmpty()
                        Text("Ingresa tu contraseña para acceder a $branchLabel.")
                        OutlinedTextField(
                            value = reauthPassword,
                            onValueChange = onReauthPasswordChange,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Contraseña") },
                            singleLine = true,
                            enabled = !branchSwitchLoading
                        )
                    } else {
                        Text("Selecciona la sucursal en la que deseas operar.")
                        BranchSelector(
                            branches = availableBranches,
                            selectedBranchId = pendingBranchSwitchId ?: activeBranchId,
                            onBranchSelected = onBranchSelected,
                            enabled = !branchSwitchLoading,
                            label = null
                        )
                    }
                    if (branchSwitchError != null) {
                        Text(
                            text = branchSwitchError,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (branchSwitchLoading) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "Cambiando sucursal…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (pendingReauthBranchId != null) {
                    TextButton(
                        onClick = onConfirmBranchReauth,
                        enabled = !branchSwitchLoading
                    ) {
                        Text("Confirmar")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissBranchSwitch, enabled = !branchSwitchLoading) {
                    Text("Cancelar")
                }
            }
        )
    }

    if (showBcvAdminDialog) {
        AlertDialog(
            onDismissRequest = onDismissBcvAdminDialog,
            title = { Text("Tasa BCV (administrador)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "La tasa automática se mantiene fija hasta las 7:00 p.m. " +
                            "(hora de Caracas) y luego se actualiza desde bcv.org.ve.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (bcvManualOverride) {
                        Text(
                            text = "Modo manual activo: el sistema no actualizará la tasa automáticamente.",
                            style = MaterialTheme.typography.bodySmall,
                            color = BrandWarning
                        )
                    }
                    OutlinedTextField(
                        value = bcvAdminRateText,
                        onValueChange = onBcvAdminRateChange,
                        label = { Text("Tasa Bs/USD") },
                        singleLine = true,
                        enabled = !bcvAdminSaving,
                        modifier = Modifier.fillMaxWidth()
                    )
                    bcvAdminError?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = onSaveManualBcvRate,
                    enabled = !bcvAdminSaving
                ) {
                    if (bcvAdminSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Guardar manual")
                    }
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (bcvManualOverride) {
                        TextButton(
                            onClick = onRestoreAutomaticBcv,
                            enabled = !bcvAdminSaving
                        ) {
                            Text("Automático")
                        }
                    }
                    TextButton(
                        onClick = onDismissBcvAdminDialog,
                        enabled = !bcvAdminSaving
                    ) {
                        Text("Cancelar")
                    }
                }
            }
        )
    }

    Scaffold(
        topBar = {
            BrandAppTopBar(
                subtitle = subtitle,
                onRefreshBcv = onRefreshBcv,
                onLogout = onLogout,
                bcvRefreshing = bcvRefreshing && !branchSwitchLoading
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
                Text(
                    text = "Menú principal",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(12.dp))
                HubDailySnapshotCard(
                    bcvLabel = bcvLabel,
                    bcvRefreshing = bcvRefreshing && !branchSwitchLoading,
                    branchKpis = branchSalesKpis,
                    kpiLoading = branchKpisLoading && !branchSwitchLoading,
                    showBranchKpis = showBranchKpis,
                    onEditBcv = onOpenBcvAdminDialog
                )
                if (showClosingExcelReminder) {
                    Spacer(Modifier.height(10.dp))
                    ClosingExcelReminderBanner(
                        message = CLOSING_EXCEL_REMINDER_MESSAGE,
                        exporting = exportingClosingExcel,
                        onDownloadClick = {
                            scope.launch {
                                if (onPrepareClosingExcelExport()) {
                                    exportLauncher.launch(suggestedClosingExportFileName())
                                }
                            }
                        }
                    )
                }
                if (canSwitchBranch && activeBranchLabel.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onOpenBranchSwitch,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Store,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Cambiar sucursal · $activeBranchLabel")
                    }
                }
                Spacer(Modifier.height(16.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    itemsIndexed(
                        items = items,
                        key = { _, item -> item.destination.name },
                        span = { index, _ ->
                            val isLonelyLast = index == items.lastIndex && items.size % columns != 0
                            GridItemSpan(if (isLonelyLast) columns else 1)
                        }
                    ) { index, item ->
                        val isLonelyLast = index == items.lastIndex && items.size % columns != 0
                        HubMenuCard(
                            item = item,
                            onClick = { onNavigate(item.destination) },
                            modifier = if (isLonelyLast) {
                                Modifier
                                    .fillMaxWidth(0.48f)
                                    .fillMaxHeight()
                            } else {
                                Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HubMenuCard(
    item: HubMenuItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .height(HubCardHeight)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier.height(40.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                if (item.showAlertBell) {
                    Icon(
                        imageVector = Icons.Default.Notifications,
                        contentDescription = "Alerta",
                        modifier = Modifier
                            .size(18.dp)
                            .align(Alignment.TopEnd),
                        tint = BrandWarning
                    )
                }
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
