package com.inventario.app.ui.hub

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.inventario.app.data.entity.CashClosingAlertType
import com.inventario.app.data.entity.UserRole
import com.inventario.app.data.repository.ReportsRepository
import com.inventario.app.ui.theme.AccessCodeDialog
import com.inventario.app.ui.theme.AppScreenBackground
import com.inventario.app.ui.theme.BcvRateBanner
import com.inventario.app.ui.theme.BrandAppTopBar
import com.inventario.app.ui.theme.BrandWarning
import com.inventario.app.ui.theme.screenHorizontalPadding
import com.inventario.app.ui.theme.screenVerticalPadding

private val HubCardMinHeight = 148.dp

enum class HubDestination {
    INVENTORY,
    CASH_CLOSING,
    REPORTS,
    USERS
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
    bcvLabel: String,
    bcvRefreshing: Boolean,
    cashClosingAlert: CashClosingAlertType? = null,
    pendingReportsCount: Int = 0,
    onNavigate: (HubDestination) -> Unit,
    onRefreshBcv: () -> Unit,
    onLogout: () -> Unit
) {
    val roleLabel = if (role == UserRole.ADMIN) "Administrador" else "Consulta"
    val subtitle = "$username · $roleLabel"
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
        add(
            HubMenuItem(
                destination = HubDestination.REPORTS,
                title = "Reportes",
                subtitle = if (pendingReportsCount > 0) {
                    "Cierres pendientes de validar"
                } else {
                    "KPIs y ventas"
                },
                icon = Icons.Default.Assessment,
                showAlertBell = pendingReportsCount > 0
            )
        )
        if (role == UserRole.ADMIN) {
            add(
                HubMenuItem(
                    destination = HubDestination.USERS,
                    title = "Usuarios",
                    subtitle = "Gestionar consulta",
                    icon = Icons.Default.People
                )
            )
        }
    }
    val columns = 2

    var showReportsAccessDialog by remember { mutableStateOf(false) }
    var reportsAccessError by remember { mutableStateOf<String?>(null) }

    fun onMenuItemClick(destination: HubDestination) {
        if (destination == HubDestination.REPORTS) {
            reportsAccessError = null
            showReportsAccessDialog = true
        } else {
            onNavigate(destination)
        }
    }

    if (showReportsAccessDialog) {
        AccessCodeDialog(
            title = "Acceso a Reportes",
            description = "Ingresa la clave de acceso para abrir el módulo de reportes.",
            error = reportsAccessError,
            onDismiss = {
                showReportsAccessDialog = false
                reportsAccessError = null
            },
            onConfirm = { code ->
                if (code == ReportsRepository.VERIFICATION_CODE) {
                    showReportsAccessDialog = false
                    reportsAccessError = null
                    onNavigate(HubDestination.REPORTS)
                } else {
                    reportsAccessError = "Clave de acceso incorrecta."
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
                bcvRefreshing = bcvRefreshing
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
                BcvRateBanner(
                    label = bcvLabel,
                    refreshing = bcvRefreshing
                )
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
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            HubMenuCard(
                                item = item,
                                onClick = { onMenuItemClick(item.destination) },
                                modifier = if (isLonelyLast) {
                                    Modifier.fillMaxWidth(0.48f)
                                } else {
                                    Modifier.fillMaxWidth()
                                }
                            )
                        }
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
            .fillMaxWidth()
            .heightIn(min = HubCardMinHeight)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(contentAlignment = Alignment.TopEnd) {
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
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                text = item.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                minLines = 2,
                maxLines = 2
            )
        }
    }
}
