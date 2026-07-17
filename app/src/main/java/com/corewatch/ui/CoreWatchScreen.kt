package com.corewatch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import com.corewatch.BatterySession
import com.corewatch.MonitorViewModel
import com.corewatch.R
import com.corewatch.ignoreBatteryOptimizationsIntent
import com.corewatch.isIgnoringBatteryOptimizations
import com.corewatch.monitor.CpuClock
import com.corewatch.monitor.DeviceInfo
import com.corewatch.monitor.LiveMetrics
import com.corewatch.ui.components.CoreGlyph
import com.corewatch.ui.components.CpuCard
import com.corewatch.ui.components.CpuCoresCard
import com.corewatch.ui.components.GlowDot
import com.corewatch.ui.components.HistoryCharts
import com.corewatch.ui.components.IdentityHeader
import com.corewatch.ui.components.RamCard
import com.corewatch.ui.components.PowerThermalCard
import com.corewatch.ui.components.SystemInfoPanel
import com.corewatch.ui.components.AccentPicker
import com.corewatch.ui.components.BackgroundGuardBanner
import com.corewatch.ui.theme.LocalPalette
import com.corewatch.ui.theme.TextPrimary
import com.corewatch.ui.theme.ThemeId
import com.corewatch.ui.theme.mono

private const val HISTORY_SIZE = 60
private const val BACK_EXIT_WINDOW_MS = 2_000L
private const val PREFS = "corewatch"
private const val KEY_GUARD_DISMISSED = "batt_opt_dismissed"
private val GAP = 14.dp

@Composable
fun CoreWatchScreen(
    viewModel: MonitorViewModel = viewModel(),
    selectedTheme: ThemeId = LocalPalette.current.id,
    onThemeChange: (ThemeId) -> Unit = {},
    onExit: () -> Unit = {},
) {
    val metrics by viewModel.metrics.collectAsStateWithLifecycle()
    val info = viewModel.deviceInfo
    val session = viewModel.batterySession

    // Back exits only on a deliberate double-press; a single back arms a short window.
    val context = LocalContext.current
    var lastBackAt by remember { mutableStateOf(0L) }
    BackHandler {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBackAt < BACK_EXIT_WINDOW_MS) {
            onExit()
        } else {
            lastBackAt = now
            Toast.makeText(context, context.getString(R.string.back_to_exit), Toast.LENGTH_SHORT).show()
        }
    }

    // Rolling window of recent CPU peak frequencies, feeding the sparkline.
    val history = remember { mutableStateListOf<Float>() }
    LaunchedEffect(metrics) {
        metrics.cpu.currentMaxMhz?.let { mhz ->
            history.add(mhz.toFloat())
            if (history.size > HISTORY_SIZE) history.removeAt(0)
        }
    }

    // Battery-optimization guard: prompt to allowlist the app so background capture isn't cut short.
    // Hidden once the exemption is granted or the user dismisses it for this session.
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var guardDismissed by remember { mutableStateOf(prefs.getBoolean(KEY_GUARD_DISMISSED, false)) }
    var ignoringBattOpt by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    val exemptionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { ignoringBattOpt = isIgnoringBatteryOptimizations(context) }
    val guard: @Composable () -> Unit = {
        if (!ignoringBattOpt && !guardDismissed) {
            BackgroundGuardBanner(
                onAllow = { exemptionLauncher.launch(ignoreBatteryOptimizationsIntent(context)) },
                onDismiss = {
                    guardDismissed = true
                    prefs.edit().putBoolean(KEY_GUARD_DISMISSED, true).apply()
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    val pal = LocalPalette.current
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(pal.bgTop, pal.bgBottom))),
    ) {
        val wide = maxWidth >= 600.dp
        val outer = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 16.dp, vertical = 6.dp)

        // Session history charts, wired once and slotted into whichever layout is active.
        val charts: @Composable () -> Unit = {
            HistoryCharts(
                cpuPoints = viewModel.cpuHistory,
                cpuMaxMhz = info.maxClockMhz,
                ramPoints = viewModel.ramHistory,
                ramTotalBytes = viewModel.ramTotalBytes,
                tempPoints = viewModel.tempHistory,
                gaps = viewModel.gapIndices,
                intervalSec = viewModel.historyIntervalSec,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (wide) {
            LandscapeLayout(outer, info, metrics, session, history, charts, guard, selectedTheme, onThemeChange)
        } else {
            PortraitLayout(outer, info, metrics, session, history, charts, guard, selectedTheme, onThemeChange)
        }
    }
}

@Composable
private fun PortraitLayout(
    modifier: Modifier,
    info: DeviceInfo,
    metrics: LiveMetrics,
    session: BatterySession,
    history: List<Float>,
    charts: @Composable () -> Unit,
    guard: @Composable () -> Unit,
    selectedTheme: ThemeId,
    onThemeChange: (ThemeId) -> Unit,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(GAP),
    ) {
        Header(selectedTheme, onThemeChange)
        guard()
        IdentityHeader(info, Modifier.fillMaxWidth())
        CpuCard(metrics.cpu, history, Modifier.fillMaxWidth())
        if (metrics.cpu.hasPerCoreData) {
            CpuCoresCard(metrics.cpu, Modifier.fillMaxWidth())
        }
        RamCard(metrics, Modifier.fillMaxWidth())
        PowerThermalCard(metrics.battery, metrics.thermal, session, Modifier.fillMaxWidth())
        charts()
        SystemInfoPanel(info, Modifier.fillMaxWidth())
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun LandscapeLayout(
    modifier: Modifier,
    info: DeviceInfo,
    metrics: LiveMetrics,
    session: BatterySession,
    history: List<Float>,
    charts: @Composable () -> Unit,
    guard: @Composable () -> Unit,
    selectedTheme: ThemeId,
    onThemeChange: (ThemeId) -> Unit,
) {
    Column(modifier) {
        Header(selectedTheme, onThemeChange)
        guard()
        Spacer(Modifier.height(GAP))
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(GAP),
        ) {
            // Left: live telemetry — all three tiles visible; charts below (scroll to reveal).
            Column(
                modifier = Modifier.weight(1.15f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(GAP),
            ) {
                IdentityHeader(info, Modifier.fillMaxWidth(), compact = true)
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(GAP),
                ) {
                    CpuCard(metrics.cpu, history, Modifier.weight(1f).fillMaxHeight())
                    RamCard(metrics, Modifier.weight(1f).fillMaxHeight())
                }
                if (metrics.cpu.hasPerCoreData) {
                    CpuCoresCard(metrics.cpu, Modifier.fillMaxWidth())
                }
                PowerThermalCard(metrics.battery, metrics.thermal, session, Modifier.fillMaxWidth())
                charts()
            }
            // Right: static system info (scrolls if the screen is short).
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
            ) {
                SystemInfoPanel(info, Modifier.fillMaxWidth())
            }
        }
    }
}

/** Whether the kernel exposed any per-core signal (clock or load) worth showing. */
private val CpuClock.hasPerCoreData: Boolean
    get() = perCoreMhz.isNotEmpty() || perCoreLoad.any { !it.isNaN() }

@Composable
private fun Header(selectedTheme: ThemeId, onThemeChange: (ThemeId) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoreGlyph(diameter = 26.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            text = "CoreWatch",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
        )
        Spacer(Modifier.weight(1f))
        AccentPicker(selectedTheme, onThemeChange)
        Spacer(Modifier.width(12.dp))
        GlowDot(color = LocalPalette.current.accent, pulse = true)
        Spacer(Modifier.width(8.dp))
        Text(
            text = "LIVE · 1s",
            style = MaterialTheme.typography.labelMedium.mono(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.sp,
        )
    }
}
