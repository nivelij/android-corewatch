package com.corewatch.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

/**
 * CoreWatch uses a fixed "instrument-like" dark theme (independent of the system light/dark setting
 * and Material You dynamic color). The [palette] selects the accent family and background gradient;
 * neutral surfaces stay constant so every theme reads as the same dark UI.
 */
@Composable
fun CoreWatchTheme(
    palette: CorePalette = EmberPalette,
    content: @Composable () -> Unit,
) {
    val scheme = remember(palette) { schemeFor(palette) }
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(
            colorScheme = scheme,
            typography = Typography,
            content = content,
        )
    }
}

private fun schemeFor(p: CorePalette) = darkColorScheme(
    primary = p.accent,
    onPrimary = Color(0xFF10130A),
    secondary = p.accentDeep,
    secondaryContainer = Color(0xFF1B1B1B),
    onSecondaryContainer = p.accent,
    background = p.bgTop,
    onBackground = TextPrimary,
    surface = Panel,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = TextMuted,
    error = StatusHot,
    outline = PanelBorder,
)
