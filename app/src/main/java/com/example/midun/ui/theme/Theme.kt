package com.example.midun.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = CardBg,
    primaryContainer = PrimaryLight,
    secondary = Accent,
    onSecondary = CardBg,
    background = Surface,
    surface = CardBg,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    error = Danger,
)

@Composable
fun MiDunTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography,
        content = content
    )
}