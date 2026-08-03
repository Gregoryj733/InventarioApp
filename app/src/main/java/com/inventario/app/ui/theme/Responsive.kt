package com.inventario.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object ScreenBreakpoints {
    const val VERY_COMPACT = 360
    const val COMPACT = 400
}

@Composable
fun screenWidthDp(): Int = LocalConfiguration.current.screenWidthDp

@Composable
fun isVeryCompactWidth(): Boolean = screenWidthDp() < ScreenBreakpoints.VERY_COMPACT

@Composable
fun isCompactWidth(): Boolean = screenWidthDp() < ScreenBreakpoints.COMPACT

@Composable
fun screenHorizontalPadding(): Dp = when {
    isVeryCompactWidth() -> 12.dp
    isCompactWidth() -> 14.dp
    else -> 16.dp
}

@Composable
fun screenVerticalPadding(): Dp = when {
    isCompactWidth() -> 10.dp
    else -> 12.dp
}
