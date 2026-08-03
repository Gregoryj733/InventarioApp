package com.inventario.app.ui.hub

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PointOfSale
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
import androidx.compose.ui.unit.dp
import com.inventario.app.data.entity.UserRole
import com.inventario.app.ui.theme.AppScreenBackground
import com.inventario.app.ui.theme.BrandAppTopBar
import com.inventario.app.ui.theme.isCompactWidth
import com.inventario.app.ui.theme.screenHorizontalPadding
import com.inventario.app.ui.theme.screenVerticalPadding

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
    val icon: ImageVector
)

@Composable
fun MainHubScreen(
    username: String,
    role: UserRole,
    bcvLabel: String,
    bcvRefreshing: Boolean,
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
                subtitle = "Cuadre diario",
                icon = Icons.Default.PointOfSale
            )
        )
        add(
            HubMenuItem(
                destination = HubDestination.REPORTS,
                title = "Reportes",
                subtitle = "KPIs y ventas",
                icon = Icons.Default.Assessment
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
    val columns = if (isCompactWidth()) 2 else 2

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
                Text(
                    text = bcvLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(items, key = { it.destination.name }) { item ->
                        HubMenuCard(item = item, onClick = { onNavigate(item.destination) })
                    }
                }
            }
        }
    }
}

@Composable
private fun HubMenuCard(
    item: HubMenuItem,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
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
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )
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
                textAlign = TextAlign.Center
            )
        }
    }
}
