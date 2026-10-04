package com.pixelmode.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AmoledColors = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    secondary = Color(0xFFB9B9C0),
    onSecondary = Color.Black,
    background = Color.Black,
    onBackground = Color(0xFFECECEF),
    surface = Color(0xFF0B0B0C),
    onSurface = Color(0xFFECECEF),
    surfaceVariant = Color(0xFF161618),
    onSurfaceVariant = Color(0xFFB9B9C0),
    outline = Color(0xFF3A3A3E),
    error = Color(0xFFFF5449),
    onError = Color.Black,
)

@Composable
fun PixelModeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AmoledColors, content = content)
}
