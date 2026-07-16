package com.corewatch.ui.theme

import androidx.compose.ui.graphics.Color

// ---- "Ember" warm dark palette (gold/amber identity, distinct from the cyan/violet family) ----
val OledBlack = Color(0xFF000000)       // true black background for OLED panels
val Panel = Color(0xFF141414)           // neutral dark — reads the same under any accent
val PanelBorder = Color(0x14FFFFFF)     // ~8% white hairline
val TextPrimary = Color(0xFFF3F1EC)
val TextMuted = Color(0xFF9A968E)
val TextLabel = Color(0xFF726F66)

// Accent + semantic
val Accent = Color(0xFFFFC24B)          // gold — primary accent + info values
val AccentDeep = Color(0xFFFF8A3D)      // ember
val StatusNormal = Color(0xFF37D08A)
val StatusWarm = Color(0xFFFFB020)
val StatusHot = Color(0xFFFF5C6E)

/** Warm ember→gold ramp for the glyph, sparkline, progress and hero numbers (Ember default). */
val AccentRamp = listOf(Color(0xFFFF6B3D), Color(0xFFFFA02C), Color(0xFFFFD36B))

// Neutral surfaces shared across all themes.
val SurfaceVariant = Color(0xFF181818)
