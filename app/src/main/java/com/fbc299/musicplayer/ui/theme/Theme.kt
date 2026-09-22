package com.fbc299.musicplayer.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Retro 风格：深底 + 紫色主色（Material You 种子色）
private val RetroDarkColors = darkColorScheme(
    primary = Color(0xFFB39DFF),
    onPrimary = Color(0xFF1D1047),
    primaryContainer = Color(0xFF342564),
    onPrimaryContainer = Color(0xFFE9DDFF),
    secondary = Color(0xFFCCC2DB),
    secondaryContainer = Color(0xFF454456),
    background = Color(0xFF0F0F10),
    surface = Color(0xFF121214),
    surfaceVariant = Color(0xFF1C1C1F),
    onBackground = Color(0xFFE6E0EA),
    onSurface = Color(0xFFE6E0EA),
    onSurfaceVariant = Color(0xFFC9C4D0)
)

private val RetroLightColors = lightColorScheme(
    primary = Color(0xFF5B3FD6),
    secondary = Color(0xFF5C5B66),
    background = Color(0xFFFCF8FF),
    surface = Color(0xFFFCF8FF)
)

@Composable
fun MusicTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        Build.VERSION.SDK_INT >= 32 && darkTheme ->
            dynamicDarkColorScheme(LocalContext.current)
        Build.VERSION.SDK_INT >= 32 && !darkTheme ->
            dynamicLightColorScheme(LocalContext.current)
        darkTheme -> RetroDarkColors
        else -> RetroLightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = RetroTypography,
        content = content
    )
}
