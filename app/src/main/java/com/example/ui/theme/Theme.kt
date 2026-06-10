package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class AppTheme {
    LIGHT, DARK, AMOLED
}

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1D5AAB), // Classic wiki/kiwix blue
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E2FF),
    onPrimaryContainer = Color(0xFF001B3E),
    secondary = Color(0xFF5A5F6E),
    onSecondary = Color.White,
    background = Color(0xFFF7F9FF), // Warm paper light background
    surface = Color.White,
    onBackground = Color(0xFF191C21),
    onSurface = Color(0xFF191C21),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44474E),
    outline = Color(0xFF74777F)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFD0BCFF), // Beautiful immersive purple
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF49454F),
    onPrimaryContainer = Color(0xFFD0BCFF),
    secondary = Color(0xFFBAC3FF), // Soft lavender cool blue
    onSecondary = Color(0xFF1B2C66),
    background = Color(0xFF0F1113), // Immersive Obsidian Dark
    surface = Color(0xFF1C1B1F), // Immersive Surface Dark gray
    onBackground = Color(0xFFE2E2E6),
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF2B2930), // Navigation bar block bg
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF49454F)
)

private val AmoledColorScheme = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF49454F),
    onPrimaryContainer = Color(0xFFD0BCFF),
    secondary = Color(0xFFBAC3FF),
    onSecondary = Color(0xFF1B2C66),
    background = Color(0xFF000000), // Perfect pitch black
    surface = Color(0xFF121212), // Deep dark gray
    onBackground = Color.White,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1C1B1F),
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF49454F)
)

@Composable
fun MyApplicationTheme(
    appTheme: AppTheme = AppTheme.DARK,
    content: @Composable () -> Unit,
) {
    val colorScheme = when (appTheme) {
        AppTheme.LIGHT -> LightColorScheme
        AppTheme.DARK -> DarkColorScheme
        AppTheme.AMOLED -> AmoledColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
