package com.example.dpi_volar.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val CreamColorScheme = lightColorScheme(
    primary = WarmBrown,
    onPrimary = Color.White,
    secondary = WarmBrownDark,
    onSecondary = Color.White,
    tertiary = AccentGreen,
    onTertiary = Color.White,
    background = CreamBackground,
    onBackground = WarmBrownDark,
    surface = CreamSurface,
    onSurface = WarmBrownDark,
    surfaceVariant = CreamSurfaceVariant,
    onSurfaceVariant = WarmBrownLight,
    outline = WarmBrownLight
)

@Composable
fun DPIVolarTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = CreamColorScheme, // siempre crema, sin importar el tema del sistema
        typography = Typography,
        content = content
    )
}