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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun BrandLogoVector(
    modifier: Modifier = Modifier,
    height: Dp = 40.dp,
    forLightBackground: Boolean = true
) {
    val titleColor = if (forLightBackground) BrandRoyalBlue else Color.White
    val subtitleColor = BrandGold
    val dividerColor = BrandGold
    val titleSize = (height.value * 0.28f).sp
    val subtitleSize = (height.value * 0.24f).sp
    val iconSize = height * 0.92f
    val gap = height * 0.1f

    Row(
        modifier = modifier.height(height),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        BrandDropIcon(
            modifier = Modifier
                .height(iconSize)
                .width(iconSize * 0.72f),
            gold = BrandGold,
            goldDark = BrandGoldDark,
            gray = BrandGray,
            grayLight = BrandGrayLight
        )
        Spacer(Modifier.width(gap))
        Column(
            modifier = Modifier.height(iconSize),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "TOTAL CARE",
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
                text = "AUTOMOTRIZ",
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
private fun BrandDropIcon(
    modifier: Modifier = Modifier,
    gold: Color,
    goldDark: Color,
    gray: Color,
    grayLight: Color
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w * 0.5f

        val drop = Path().apply {
            moveTo(cx, h * 0.04f)
            cubicTo(w * 0.92f, h * 0.22f, w * 0.9f, h * 0.72f, cx, h * 0.96f)
            cubicTo(w * 0.1f, h * 0.72f, w * 0.08f, h * 0.22f, cx, h * 0.04f)
            close()
        }

        drawPath(
            path = drop,
            brush = Brush.verticalGradient(
                colors = listOf(gold, goldDark),
                startY = 0f,
                endY = h
            )
        )

        val facetRight = Path().apply {
            moveTo(cx, h * 0.08f)
            lineTo(w * 0.82f, h * 0.34f)
            lineTo(w * 0.72f, h * 0.78f)
            lineTo(cx, h * 0.9f)
            close()
        }
        drawPath(
            path = facetRight,
            brush = Brush.linearGradient(
                colors = listOf(grayLight.copy(alpha = 0.85f), gray.copy(alpha = 0.95f)),
                start = Offset(cx, h * 0.1f),
                end = Offset(w, h)
            )
        )

        val facetLeft = Path().apply {
            moveTo(cx, h * 0.1f)
            lineTo(w * 0.22f, h * 0.36f)
            lineTo(w * 0.34f, h * 0.76f)
            lineTo(cx, h * 0.88f)
            close()
        }
        drawPath(
            path = facetLeft,
            brush = Brush.linearGradient(
                colors = listOf(gold.copy(alpha = 0.95f), goldDark),
                start = Offset(0f, h * 0.2f),
                end = Offset(cx, h)
            )
        )

        val innerDrop = Path().apply {
            moveTo(cx, h * 0.28f)
            cubicTo(w * 0.68f, h * 0.36f, w * 0.66f, h * 0.62f, cx, h * 0.72f)
            cubicTo(w * 0.34f, h * 0.62f, w * 0.32f, h * 0.36f, cx, h * 0.28f)
            close()
        }
        drawPath(
            path = innerDrop,
            color = Color.Transparent,
            style = Stroke(width = w * 0.045f)
        )
        drawPath(
            path = innerDrop,
            brush = Brush.verticalGradient(
                colors = listOf(gold.copy(alpha = 0.15f), Color.Transparent)
            ),
            style = Stroke(width = w * 0.045f)
        )

        drawPath(
            path = drop,
            color = goldDark,
            style = Stroke(width = w * 0.03f)
        )
    }
}
