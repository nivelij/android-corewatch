package com.corewatch.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

val Typography = Typography()

/** Tabular figures so a value re-rendered every second keeps constant width (no jitter). */
fun TextStyle.tabularFigures(): TextStyle = copy(fontFeatureSettings = "tnum")

/** Monospaced + tabular — the "readout" look for the big live numbers. */
fun TextStyle.mono(): TextStyle =
    copy(fontFamily = FontFamily.Monospace, fontFeatureSettings = "tnum")
