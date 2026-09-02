package com.inventario.app.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val SupraNavy = Color(0xFF1E3A8A)
private val SupraNavyDark = Color(0xFF0F2440)
private val SupraFlame = Color(0xFFF97316)
private val SupraFlameHot = Color(0xFFEF4444)
private val SupraFlameCore = Color(0xFFFDE047)

@Composable
fun SupraPartsLogoVector(
    modifier: Modifier = Modifier,
    height: Dp = 40.dp,
    forLightBackground: Boolean = true
) {
    val titleColor = if (forLightBackground) BrandRoyalBlue else Color.White
    val subtitleColor = BrandGold
    val dividerColor = BrandGold
    val titleSize = (height.value * 0.28f).sp
    val subtitleSize = (height.value * 0.24f).sp
    val iconSize = height * 0.98f
    val gap = height * 0.1f
    val iconWidth = iconSize * 0.82f

    Row(
        modifier = modifier.height(height),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        SupraPartsToolIcon(
            modifier = Modifier
                .height(iconSize)
                .width(iconWidth),
            forLightBackground = forLightBackground
        )
        Spacer(Modifier.width(gap))
        Column(
            modifier = Modifier.height(iconSize),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "SUPRA",
                color = titleColor,
                fontSize = titleSize,
                fontWeight = FontWeight.ExtraBold,
                fontStyle = FontStyle.Italic,
                letterSpacing = 0.5.sp,
                maxLines = 1
            )
            Spacer(Modifier.height(height * 0.04f))
            HorizontalDivider(
                modifier = Modifier.width(height * 2.2f),
                thickness = maxOf(1.dp, height * 0.025f),
                color = dividerColor
            )
            Spacer(Modifier.height(height * 0.03f))
            Text(
                text = "PARTS",
                color = subtitleColor,
                fontSize = subtitleSize,
                fontWeight = FontWeight.Bold,
                fontStyle = FontStyle.Italic,
                letterSpacing = 1.sp,
                maxLines = 1
            )
        }
    }
}

@Composable
fun SupraPartsLogoSplash(modifier: Modifier = Modifier) {
    SupraPartsLogoVector(
        modifier = modifier,
        height = 80.dp,
        forLightBackground = true
    )
}

/**
 * Emblema multi-herramienta simplificado (legible en tamaño pequeño).
 * Misma caja que [BrandDropIcon]: alto × 0.72 ancho.
 */
@Composable
fun SupraPartsToolIcon(
    modifier: Modifier = Modifier,
    forLightBackground: Boolean = true
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w * 0.5f
        val stroke = w * 0.05f

        // Cuerpo navaja suiza (cápsula horizontal)
        val body = Path().apply {
            moveTo(w * 0.12f, h * 0.58f)
            cubicTo(w * 0.08f, h * 0.52f, w * 0.10f, h * 0.44f, w * 0.18f, h * 0.42f)
            lineTo(w * 0.82f, h * 0.42f)
            cubicTo(w * 0.90f, h * 0.44f, w * 0.92f, h * 0.52f, w * 0.88f, h * 0.58f)
            cubicTo(w * 0.90f, h * 0.64f, w * 0.86f, h * 0.70f, w * 0.78f, h * 0.72f)
            lineTo(w * 0.22f, h * 0.72f)
            cubicTo(w * 0.14f, h * 0.70f, w * 0.10f, h * 0.64f, w * 0.12f, h * 0.58f)
            close()
        }
        drawPath(
            path = body,
            brush = Brush.horizontalGradient(
                colors = listOf(SupraFlameHot, SupraFlame, BrandGold, BrandGoldLight),
                startX = w * 0.1f,
                endX = w * 0.9f
            )
        )

        // Remaches
        drawCircle(
            color = SupraNavy.copy(alpha = 0.85f),
            radius = w * 0.045f,
            center = Offset(w * 0.22f, h * 0.57f)
        )
        drawCircle(
            color = SupraNavy.copy(alpha = 0.85f),
            radius = w * 0.045f,
            center = Offset(w * 0.78f, h * 0.57f)
        )

        // Llama central
        val flame = Path().apply {
            moveTo(cx, h * 0.08f)
            cubicTo(w * 0.68f, h * 0.14f, w * 0.72f, h * 0.30f, w * 0.62f, h * 0.38f)
            cubicTo(w * 0.56f, h * 0.32f, w * 0.52f, h * 0.28f, cx, h * 0.08f)
            close()
        }
        val flameLeft = Path().apply {
            moveTo(cx, h * 0.10f)
            cubicTo(w * 0.34f, h * 0.16f, w * 0.30f, h * 0.30f, w * 0.38f, h * 0.38f)
            cubicTo(w * 0.44f, h * 0.32f, w * 0.48f, h * 0.28f, cx, h * 0.10f)
            close()
        }
        drawPath(
            path = flame,
            brush = Brush.verticalGradient(
                colors = listOf(SupraFlameCore, SupraFlame, SupraFlameHot),
                startY = h * 0.08f,
                endY = h * 0.38f
            )
        )
        drawPath(
            path = flameLeft,
            brush = Brush.verticalGradient(
                colors = listOf(SupraFlameCore, SupraFlame, SupraFlameHot),
                startY = h * 0.10f,
                endY = h * 0.38f
            )
        )

        // Martillo (izquierda)
        val hammer = Path().apply {
            moveTo(w * 0.14f, h * 0.36f)
            lineTo(w * 0.28f, h * 0.22f)
            lineTo(w * 0.34f, h * 0.28f)
            lineTo(w * 0.22f, h * 0.42f)
            close()
        }
        drawPath(
            path = hammer,
            brush = Brush.linearGradient(
                colors = listOf(SupraNavy, SupraNavyDark),
                start = Offset(w * 0.14f, h * 0.22f),
                end = Offset(w * 0.34f, h * 0.42f)
            )
        )

        // Destornillador / llave (derecha)
        val wrench = Path().apply {
            moveTo(w * 0.72f, h * 0.24f)
            cubicTo(w * 0.80f, h * 0.20f, w * 0.88f, h * 0.26f, w * 0.86f, h * 0.34f)
            cubicTo(w * 0.82f, h * 0.40f, w * 0.74f, h * 0.38f, w * 0.70f, h * 0.32f)
            close()
        }
        drawPath(
            path = wrench,
            brush = Brush.linearGradient(
                colors = listOf(SupraNavy, SupraNavyDark),
                start = Offset(w * 0.70f, h * 0.20f),
                end = Offset(w * 0.88f, h * 0.40f)
            )
        )

        // Corona (detalle derecho)
        val crown = Path().apply {
            moveTo(w * 0.76f, h * 0.76f)
            lineTo(w * 0.80f, h * 0.68f)
            lineTo(w * 0.84f, h * 0.76f)
            lineTo(w * 0.88f, h * 0.68f)
            lineTo(w * 0.92f, h * 0.76f)
            lineTo(w * 0.92f, h * 0.82f)
            lineTo(w * 0.76f, h * 0.82f)
            close()
        }
        drawPath(path = crown, color = SupraNavy)

        // Circuito decorativo (esencia tech del logo original)
        if (forLightBackground) {
            drawLine(
                color = BrandGoldLight.copy(alpha = 0.9f),
                start = Offset(w * 0.20f, h * 0.48f),
                end = Offset(w * 0.32f, h * 0.48f),
                strokeWidth = stroke * 0.35f
            )
            drawCircle(
                color = BrandGoldLight,
                radius = w * 0.025f,
                center = Offset(w * 0.32f, h * 0.48f)
            )
            drawLine(
                color = BrandGoldLight.copy(alpha = 0.9f),
                start = Offset(w * 0.68f, h * 0.50f),
                end = Offset(w * 0.78f, h * 0.50f),
                strokeWidth = stroke * 0.35f
            )
            drawCircle(
                color = BrandGoldLight,
                radius = w * 0.025f,
                center = Offset(w * 0.68f, h * 0.50f)
            )
        }
    }
}
