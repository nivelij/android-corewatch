package com.corewatch.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * CoreWatch uses a fixed futuristic dark theme (independent of the system light/dark setting and
 * Material You dynamic color) so the look is consistent and deliberately "instrument-like".
 */
@Composable
fun CoreWatchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CoreWatchDarkScheme,
        typography = Typography,
        content = content,
    )
}
