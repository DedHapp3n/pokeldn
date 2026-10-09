package com.dedhapp3n.pokeldn.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = SignalBlue,
    onPrimary = AppBackground,
    primaryContainer = SignalBlueContainer,
    onPrimaryContainer = AppText,
    secondary = WarmAmber,
    onSecondary = AppBackground,
    secondaryContainer = WarmAmberContainer,
    onSecondaryContainer = AppText,
    tertiary = LinkGreen,
    onTertiary = AppBackground,
    tertiaryContainer = LinkGreenContainer,
    onTertiaryContainer = AppText,
    background = AppBackground,
    onBackground = AppText,
    surface = AppSurface,
    onSurface = AppText,
    surfaceContainerLowest = AppBackground,
    surfaceContainerLow = AppSurface,
    surfaceContainer = AppSurface,
    surfaceContainerHigh = AppSurfaceHigh,
    surfaceContainerHighest = AppSurfaceHigh,
    surfaceVariant = AppSurfaceHigh,
    onSurfaceVariant = AppTextMuted,
    outline = AppOutline,
    error = AppError,
    errorContainer = AppErrorContainer,
    onErrorContainer = AppText,
)

@Composable
fun PokeLDNTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
