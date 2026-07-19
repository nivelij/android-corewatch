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

enum class ThemeId { EMBER, VOLT, AZURE, INDIGO, AMETHYST, STEEL }

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

/** Cool periwinkle blue → violet on black. */
val IndigoPalette = CorePalette(
    id = ThemeId.INDIGO,
    displayName = "Indigo",
    accent = Color(0xFF8FA2FF),
    accentDeep = Color(0xFF9A6BFF),
    accentRamp = listOf(Color(0xFF5468FF), Color(0xFF7C74FF), Color(0xFFB48CFF)),
    bgTop = OledBlack,
    bgBottom = OledBlack,
)

/** Electric sky blue → pale azure on black; a bright, true blue (no violet, no green). */
val AzurePalette = CorePalette(
    id = ThemeId.AZURE,
    displayName = "Azure",
    accent = Color(0xFF3B9EFF),
    accentDeep = Color(0xFF1D6FD6),
    accentRamp = listOf(Color(0xFF1668CC), Color(0xFF3B9EFF), Color(0xFFA8D6FF)),
    bgTop = OledBlack,
    bgBottom = OledBlack,
)

/** Electric lime → pale green on black; a terminal / high-voltage look. */
val VoltPalette = CorePalette(
    id = ThemeId.VOLT,
    displayName = "Volt",
    accent = Color(0xFFB5F23D),
    accentDeep = Color(0xFF5C9A16),
    accentRamp = listOf(Color(0xFF3E8E10), Color(0xFF8FD62A), Color(0xFFD6F98A)),
    bgTop = OledBlack,
    bgBottom = OledBlack,
)

/** Saturated violet → lilac on black; purple where Indigo leans blue. */
val AmethystPalette = CorePalette(
    id = ThemeId.AMETHYST,
    displayName = "Amethyst",
    accent = Color(0xFFC084FC),
    accentDeep = Color(0xFF7C3AED),
    accentRamp = listOf(Color(0xFF6D28D9), Color(0xFFA855F7), Color(0xFFD8B4FE)),
    bgTop = OledBlack,
    bgBottom = OledBlack,
)

/** Neutral graphite → silver; a monochrome "no-color" instrument look. */
val SteelPalette = CorePalette(
    id = ThemeId.STEEL,
    displayName = "Steel",
    accent = Color(0xFFC7D0DC),
    accentDeep = Color(0xFF7B8794),
    accentRamp = listOf(Color(0xFF5B6673), Color(0xFF99A6B5), Color(0xFFE2E8F0)),
    bgTop = OledBlack,
    bgBottom = OledBlack,
)

/** All selectable themes, in display order (warm → cool → neutral). */
val ThemeCatalog = listOf(
    EmberPalette, VoltPalette, AzurePalette,
    IndigoPalette, AmethystPalette, SteelPalette,
)

fun paletteFor(id: ThemeId): CorePalette = ThemeCatalog.firstOrNull { it.id == id } ?: EmberPalette

/** The active palette, provided at the theme root and read by every accent-aware component. */
val LocalPalette = staticCompositionLocalOf { EmberPalette }
