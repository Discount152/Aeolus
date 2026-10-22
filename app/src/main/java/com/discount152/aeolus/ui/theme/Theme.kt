package com.discount152.aeolus.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AeolusColorScheme = darkColorScheme(
    primary = PhosphorGreen,
    onPrimary = TerminalBackground,
    secondary = PhosphorGreen,
    onSecondary = TerminalBackground,
    background = TerminalBackground,
    onBackground = PhosphorGreen,
    surface = TerminalElevated,
    onSurface = PhosphorGreen,
    surfaceVariant = TerminalBorder,
    onSurfaceVariant = TerminalMuted,
    outline = TerminalBorder,
    error = Color(0xFFFF6B6B),
)

@Composable
fun AeolusTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = AeolusColorScheme,
        typography = AeolusTypography,
        content = content,
    )
}
