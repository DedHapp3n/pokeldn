package com.dedhapp3n.pokeldn.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DeviceColorScheme = darkColorScheme(
    primary = ShellRedDark,
    onPrimary = CreamPanel,
    primaryContainer = ShellRed,
    onPrimaryContainer = CreamPanel,
    secondary = DeviceAmber,
    onSecondary = DeviceInk,
    secondaryContainer = CreamPanelDark,
    onSecondaryContainer = DeviceInk,
    tertiary = IndicatorCyan,
    onTertiary = DeviceInk,
    tertiaryContainer = ScreenBlack,
    onTertiaryContainer = ScreenText,
    background = ShellRed,
    onBackground = CreamPanel,
    surface = CreamPanel,
    onSurface = DeviceInk,
    surfaceContainerLowest = ScreenBlack,
    surfaceContainerLow = CreamPanel,
    surfaceContainer = CreamPanel,
    surfaceContainerHigh = CreamPanelDark,
    surfaceContainerHighest = CreamPanelDark,
    surfaceVariant = CreamPanelDark,
    onSurfaceVariant = DeviceInkMuted,
    outline = DeviceBezel,
    error = IndicatorRed,
    errorContainer = ShellRedDark,
    onErrorContainer = CreamPanel,
)

@Composable
fun PokeLDNTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DeviceColorScheme,
        typography = Typography,
        content = content
    )
}
