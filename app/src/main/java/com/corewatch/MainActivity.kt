package com.corewatch

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
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

        // Keep telemetry collection alive while the app is off-screen.
        ContextCompat.startForegroundService(this, Intent(this, MonitoringService::class.java))

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        setContent {
            // Selected theme persists across launches; re-read on each (re)create.
            var themeId by remember {
                mutableStateOf(
                    runCatching { ThemeId.valueOf(prefs.getString(KEY_THEME, ThemeId.EMBER.name)!!) }
                        .getOrDefault(ThemeId.EMBER),
                )
            }

            // Ask once for notification permission (API 33+) so the ongoing notification is visible;
            // the service collects regardless of the answer.
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) {}
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            CoreWatchTheme(palette = paletteFor(themeId)) {
                CoreWatchScreen(
                    selectedTheme = themeId,
                    onThemeChange = { id ->
                        themeId = id
                        prefs.edit().putString(KEY_THEME, id.name).apply()
                    },
                    onExit = ::exitApp,
                )
            }
        }
    }

    /** Deliberate exit (double-back): stop collection + service, drop the task. */
    private fun exitApp() {
        SessionCollector.stop()
        stopService(Intent(this, MonitoringService::class.java))
        finishAndRemoveTask()
    }
}
