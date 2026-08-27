package com.inventario.app.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.inventario.app.R
import com.inventario.app.data.branch.BranchTheme
import com.inventario.app.data.branch.branchDisplayName
import androidx.compose.ui.platform.LocalContext

/** Sucursal activa para branding dinámico en toda la UI autenticada. */
val LocalActiveBranchId = staticCompositionLocalOf { "total_care" }

@Composable
fun BranchBrandLogoSplash(
    branchId: String?,
    modifier: Modifier = Modifier,
    height: Dp = 96.dp
) {
    val context = LocalContext.current
    val contentDescription = branchDisplayName(branchId, context)
    when (BranchTheme.fromId(branchId)) {
        BranchTheme.SUPRA_PARTS -> Image(
            painter = painterResource(R.drawable.logo_supra_parts),
            contentDescription = contentDescription,
            modifier = modifier.height(height),
            contentScale = ContentScale.Fit
        )
        BranchTheme.TOTAL_CARE -> BrandLogoSplash(modifier = modifier)
    }
}

@Composable
fun BranchBrandLogoTopBar(
    branchId: String?,
    height: Dp = 40.dp,
    forLightBackground: Boolean = false
) {
    val context = LocalContext.current
    val contentDescription = branchDisplayName(branchId, context)
    when (BranchTheme.fromId(branchId)) {
        BranchTheme.SUPRA_PARTS -> Image(
            painter = painterResource(R.drawable.logo_supra_parts_icon),
            contentDescription = contentDescription,
            modifier = Modifier.height(height),
            contentScale = ContentScale.Fit
        )
        BranchTheme.TOTAL_CARE -> BrandLogo(height = height, forLightBackground = forLightBackground)
    }
}

@Composable
fun BranchBrandTitle(
    branchId: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    androidx.compose.material3.Text(
        text = branchDisplayName(branchId, context),
        style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
        color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
        modifier = modifier
    )
}

@Composable
fun AppBrandTitle(modifier: Modifier = Modifier) {
    androidx.compose.material3.Text(
        text = stringResource(R.string.app_name),
        style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}
