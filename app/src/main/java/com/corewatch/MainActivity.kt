package com.corewatch

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.corewatch.ui.CoreWatchScreen
import com.corewatch.ui.theme.CoreWatchTheme
import com.corewatch.ui.theme.ThemeId
import com.corewatch.ui.theme.paletteFor

private const val PREFS = "corewatch"
private const val KEY_THEME = "theme"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Dark, transparent system bars with light icons (app is always dark-themed).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        setContent {
            // Selected theme persists across launches; re-read on each (re)create.
            var themeId by remember {
                mutableStateOf(
                    runCatching { ThemeId.valueOf(prefs.getString(KEY_THEME, ThemeId.EMBER.name)!!) }
                        .getOrDefault(ThemeId.EMBER),
                )
            }
            CoreWatchTheme(palette = paletteFor(themeId)) {
                CoreWatchScreen(
                    selectedTheme = themeId,
                    onThemeChange = { id ->
                        themeId = id
                        prefs.edit().putString(KEY_THEME, id.name).apply()
                    },
                )
            }
        }
    }
}
