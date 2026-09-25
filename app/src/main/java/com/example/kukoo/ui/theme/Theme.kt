package com.example.kukoo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.kukoo.domain.Priority

private val DarkColorScheme = darkColorScheme(
    primary = Indigo80,
    onPrimary = Color(0xFF0E1A5C),
    primaryContainer = Color(0xFF26325F),
    onPrimaryContainer = Color(0xFFDDE1FF),
    background = DarkBackground,
    onBackground = DarkText,
    surface = DarkSurface,
    onSurface = DarkText,
    surfaceVariant = DarkOutline,
    onSurfaceVariant = DarkTextMuted,
    surfaceContainerHigh = DarkSurface,
    surfaceContainerHighest = DarkOutline,
    surfaceContainerLow = DarkSurface,
    surfaceContainer = DarkSurface,
    outline = DarkOutline,
    outlineVariant = DarkOutline,
    error = Danger
)

private val LightColorScheme = lightColorScheme(
    primary = Indigo40,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3E7FF),
    onPrimaryContainer = Color(0xFF0E1A5C),
    background = LightBackground,
    onBackground = LightText,
    surface = LightSurface,
    onSurface = LightText,
    surfaceVariant = Color(0xFFEDEEF3),
    onSurfaceVariant = LightTextMuted,
    surfaceContainerHigh = Color(0xFFF1F2F7),
    surfaceContainerHighest = Color(0xFFEDEEF3),
    surfaceContainerLow = LightSurface,
    surfaceContainer = LightSurface,
    outline = LightOutline,
    outlineVariant = LightOutline,
    error = Danger
)

@Composable
fun KukooTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content
    )
}

fun Priority.color(): Color = when (this) {
    Priority.HIGH -> PriorityHigh
    Priority.MEDIUM -> PriorityMedium
    Priority.LOW -> PriorityLow
}
