package com.inventario.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = BrandRoyalBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE6F8),
    onPrimaryContainer = BrandRoyalBlue,
    secondary = BrandGold,
    onSecondary = Color.White,
    secondaryContainer = BrandGoldLight.copy(alpha = 0.35f),
    onSecondaryContainer = BrandGoldDark,
    tertiary = BrandWarning,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE8C2),
    onTertiaryContainer = Color(0xFF5C3D00),
    errorContainer = Color(0xFFFFDAD4),
    onErrorContainer = Color(0xFF5C1008),
    background = BrandSand,
    onBackground = BrandInk,
    surface = Color.White,
    onSurface = BrandInk,
    surfaceVariant = Color(0xFFE8EEEF),
    onSurfaceVariant = Color(0xFF3D4A4E),
    outline = BrandRoyalBlue.copy(alpha = 0.22f)
)

private val AppTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        letterSpacing = 0.2.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.3.sp
    )
)

@Composable
fun InventarioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = AppTypography,
        content = content
    )
}
