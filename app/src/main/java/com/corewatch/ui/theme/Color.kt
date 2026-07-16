package com.corewatch.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

// ---- "Ember" warm dark palette (gold/amber identity, distinct from the cyan/violet family) ----
val BgTop = Color(0xFF14100A)
val BgBottom = Color(0xFF0A0805)
val Panel = Color(0xFF171209)
val PanelBorder = Color(0x14FFFFFF)     // ~8% white hairline
val TextPrimary = Color(0xFFF5F1E8)
val TextMuted = Color(0xFF9C9484)
val TextLabel = Color(0xFF766E5C)

// Accent + semantic
val Accent = Color(0xFFFFC24B)          // gold — primary accent + info values
val AccentDeep = Color(0xFFFF8A3D)      // ember
val StatusNormal = Color(0xFF37D08A)
val StatusWarm = Color(0xFFFFB020)
val StatusHot = Color(0xFFFF5C6E)

/** Warm ember→gold ramp for the glyph, sparkline, progress and hero numbers. */
val AccentRamp = listOf(Color(0xFFFF6B3D), Color(0xFFFFA02C), Color(0xFFFFD36B))

val CoreWatchDarkScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF241800),
    secondary = AccentDeep,
    secondaryContainer = Color(0xFF241B0E),
    onSecondaryContainer = Accent,
    background = BgTop,
    onBackground = TextPrimary,
    surface = Panel,
    onSurface = TextPrimary,
    surfaceVariant = Color(0xFF1E170D),
    onSurfaceVariant = TextMuted,
    error = StatusHot,
    outline = PanelBorder,
)
