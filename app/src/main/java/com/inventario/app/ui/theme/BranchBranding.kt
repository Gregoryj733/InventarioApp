package com.inventario.app.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.inventario.app.R
import com.inventario.app.data.branch.branchDisplayName
import com.inventario.app.data.branch.normalizeBranchId

/** Sucursal activa para branding dinámico en toda la UI autenticada. */
val LocalActiveBranchId = staticCompositionLocalOf { "total_care" }

@Composable
fun BranchBrandLogoSplash(
    branchId: String?,
    modifier: Modifier = Modifier,
    height: Dp = 96.dp
) {
    when (normalizeBranchId(branchId)) {
        "supra_parts" -> Image(
            painter = painterResource(R.drawable.logo_supra_parts),
            contentDescription = branchDisplayName(branchId),
            modifier = modifier.height(height),
            contentScale = ContentScale.Fit
        )
        else -> BrandLogoSplash(modifier = modifier)
    }
}

@Composable
fun BranchBrandLogoTopBar(
    branchId: String?,
    height: Dp = 40.dp,
    forLightBackground: Boolean = false
) {
    when (normalizeBranchId(branchId)) {
        "supra_parts" -> Image(
            painter = painterResource(R.drawable.logo_supra_parts_icon),
            contentDescription = branchDisplayName(branchId),
            modifier = Modifier.height(height),
            contentScale = ContentScale.Fit
        )
        else -> BrandLogo(height = height, forLightBackground = forLightBackground)
    }
}
