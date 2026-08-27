package com.inventario.app.ui.theme

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun BrandLogo(
    modifier: Modifier = Modifier,
    height: Dp = 40.dp,
    forLightBackground: Boolean = false
) {
    BrandLogoVector(
        modifier = modifier,
        height = height,
        forLightBackground = forLightBackground
    )
}

@Composable
fun BrandLogoSplash(modifier: Modifier = Modifier) {
    BrandLogoVector(
        modifier = modifier,
        height = 72.dp,
        forLightBackground = true
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrandAppTopBar(
    subtitle: String,
    onRefreshBcv: () -> Unit,
    onLogout: () -> Unit,
    showImportInventory: Boolean = false,
    onImportInventory: (() -> Unit)? = null,
    importEnabled: Boolean = true,
    bcvRefreshing: Boolean = false,
    showBack: Boolean = false,
    onBack: (() -> Unit)? = null
) {
    val compact = isCompactWidth()
    val logoHeight = when {
        isVeryCompactWidth() -> 32.dp
        compact -> 36.dp
        else -> 44.dp
    }
    val subtitleStyle = if (compact) {
        MaterialTheme.typography.labelSmall
    } else {
        MaterialTheme.typography.bodySmall
    }
    val onPrimary = MaterialTheme.colorScheme.onPrimary

    TopAppBar(
        navigationIcon = {
            if (showBack && onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Volver",
                        tint = onPrimary
                    )
                }
            }
        },
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BranchBrandLogoTopBar(
                    branchId = LocalActiveBranchId.current,
                    height = logoHeight,
                    forLightBackground = false
                )
                Spacer(Modifier.width(if (compact) 8.dp else 12.dp))
                Text(
                    text = subtitle,
                    style = subtitleStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        },
        actions = {
            if (showImportInventory && onImportInventory != null) {
                IconButton(onClick = onImportInventory, enabled = importEnabled) {
                    Icon(Icons.Default.CloudUpload, contentDescription = "Cargar inventario Excel")
                }
            }
            if (bcvRefreshing) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(22.dp),
                    strokeWidth = 2.dp,
                    color = onPrimary
                )
            } else {
                IconButton(onClick = onRefreshBcv) {
                    Icon(Icons.Default.Refresh, contentDescription = "Actualizar BCV")
                }
            }
            if (compact) {
                IconButton(onClick = onLogout) {
                    Icon(
                        Icons.AutoMirrored.Filled.Logout,
                        contentDescription = "Salir"
                    )
                }
            } else {
                TextButton(onClick = onLogout) {
                    Icon(
                        Icons.AutoMirrored.Filled.Logout,
                        contentDescription = null,
                        tint = onPrimary
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(text = "Salir", color = onPrimary)
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primary,
            titleContentColor = onPrimary,
            navigationIconContentColor = onPrimary,
            actionIconContentColor = onPrimary
        )
    )
}
