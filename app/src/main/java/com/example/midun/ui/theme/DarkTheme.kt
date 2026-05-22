package com.example.midun.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary        = AccentCyan,
    onPrimary      = DarkBackground,
    background     = DarkBackground,
    surface        = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    onBackground   = DarkTextPrimary,
    onSurface      = DarkTextPrimary,
    error          = AccentRed,
)

@Composable
fun MiDunDarkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography  = Typography,
        content     = content
    )
}
