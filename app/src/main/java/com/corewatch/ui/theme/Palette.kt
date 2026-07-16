package com.corewatch.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * A runtime-swappable accent + background palette. Every theme stays dark and "instrument-like";
 * only the accent family and the background gradient change. Neutral surfaces (panels, text,
 * status colors) are shared across themes (see [Color.kt]).
 */
data class CorePalette(
    val id: ThemeId,
    val displayName: String,
    val accent: Color,
    val accentDeep: Color,
    /** Warm→bright ramp used for the glyph, sparklines, progress bars and hero numbers. */
    val accentRamp: List<Color>,
    val bgTop: Color,
    val bgBottom: Color,
)

enum class ThemeId { EMBER, INDIGO, CRIMSON, TEAL }

/** The original gold/amber identity — reuses the constants in [Color.kt]. */
val EmberPalette = CorePalette(
    id = ThemeId.EMBER,
    displayName = "Ember",
    accent = Accent,
    accentDeep = AccentDeep,
    accentRamp = AccentRamp,
    bgTop = OledBlack,
    bgBottom = OledBlack,
)

/** Cool blue → violet on deep navy. */
val IndigoPalette = CorePalette(
    id = ThemeId.INDIGO,
    displayName = "Indigo",
    accent = Color(0xFF8FA2FF),
    accentDeep = Color(0xFF9A6BFF),
    accentRamp = listOf(Color(0xFF5468FF), Color(0xFF7C74FF), Color(0xFFB48CFF)),
    bgTop = OledBlack,
    bgBottom = OledBlack,
)

/** Hot red → coral on near-black. */
val CrimsonPalette = CorePalette(
    id = ThemeId.CRIMSON,
    displayName = "Crimson",
    accent = Color(0xFFFF6274),
    accentDeep = Color(0xFFC21E2E),
    accentRamp = listOf(Color(0xFFB31226), Color(0xFFFF3B4E), Color(0xFFFF8A6B)),
    bgTop = OledBlack,
    bgBottom = OledBlack,
)

/** Aqua → teal on deep green-black. */
val TealPalette = CorePalette(
    id = ThemeId.TEAL,
    displayName = "Teal",
    accent = Color(0xFF3FE0C8),
    accentDeep = Color(0xFF2AA9C0),
    accentRamp = listOf(Color(0xFF15C2A0), Color(0xFF33D6C4), Color(0xFF7CECE2)),
    bgTop = OledBlack,
    bgBottom = OledBlack,
)

/** All selectable themes, in display order. */
val ThemeCatalog = listOf(EmberPalette, IndigoPalette, CrimsonPalette, TealPalette)

fun paletteFor(id: ThemeId): CorePalette = ThemeCatalog.firstOrNull { it.id == id } ?: EmberPalette

/** The active palette, provided at the theme root and read by every accent-aware component. */
val LocalPalette = staticCompositionLocalOf { EmberPalette }
