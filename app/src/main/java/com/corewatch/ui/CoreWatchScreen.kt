package com.corewatch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
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
import com.corewatch.ui.components.SystemSpecsDialog
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

    // Which screen is showing. History is a self-contained stack over the live dashboard.
    var nav by remember { mutableStateOf<Nav>(Nav.Main) }

    // Back exits only on a deliberate double-press; a single back arms a short window.
    val context = LocalContext.current
    var lastBackAt by remember { mutableStateOf(0L) }

    // Rolling window feeding the sparkline under the CPU headline: overall load (%) where the OS
    // exposes it, else peak clock (MHz) so the sparkline still renders on devices that block it.
    val history = remember { mutableStateListOf<Float>() }
    LaunchedEffect(metrics) {
        val v = metrics.cpu.overallLoad?.let { it * 100f } ?: metrics.cpu.currentMaxMhz?.toFloat()
        v?.let {
            history.add(it)
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

    when (val n = nav) {
        Nav.History -> {
            BackHandler { nav = Nav.Main }
            HistoryScreen(
                viewModel = viewModel,
                onOpenSession = { nav = Nav.Detail(it) },
                onBack = { nav = Nav.Main },
            )
        }

        is Nav.Detail -> {
            BackHandler { nav = Nav.History }
            HistoryDetailScreen(viewModel, n.id, onBack = { nav = Nav.History })
        }

        Nav.Main -> {
            BackHandler {
                val now = SystemClock.elapsedRealtime()
                if (now - lastBackAt < BACK_EXIT_WINDOW_MS) {
                    onExit()
                } else {
                    lastBackAt = now
                    Toast.makeText(context, context.getString(R.string.back_to_exit), Toast.LENGTH_SHORT).show()
                }
            }
            val recording = viewModel.isRecording
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

                // Live session charts appear only while recording (there's no session otherwise).
                val sessionBlock: @Composable () -> Unit = {
                    if (recording) {
                        HistoryCharts(
                            cpuPoints = viewModel.cpuHistory,
                            cpuIsLoad = viewModel.cpuLoadSupported,
                            cpuMaxMhz = info.maxClockMhz,
                            ramPoints = viewModel.ramHistory,
                            ramTotalBytes = viewModel.ramTotalBytes,
                            tempPoints = viewModel.tempHistory,
                            powerPoints = viewModel.powerHistory,
                            gaps = viewModel.gapIndices,
                            intervalSec = viewModel.historyIntervalSec,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                // Fixed Start/Stop control, slotted directly under the device identity.
                val recordingControl: @Composable () -> Unit = {
                    RecordingControl(
                        recording = recording,
                        startMillis = viewModel.recordingStartMillis,
                        onStart = { viewModel.startRecording() },
                        onStop = { viewModel.stopRecording() },
                    )
                }

                val openHistory = { nav = Nav.History }
                if (wide) {
                    LandscapeLayout(outer, info, metrics, session, history, sessionBlock, recordingControl, guard, selectedTheme, onThemeChange, openHistory, recording)
                } else {
                    PortraitLayout(outer, info, metrics, session, history, sessionBlock, recordingControl, guard, selectedTheme, onThemeChange, openHistory, recording)
                }
            }
        }
    }
}

/** In-app navigation for the History stack layered over the live dashboard. */
private sealed interface Nav {
    data object Main : Nav
    data object History : Nav
    data class Detail(val id: Long) : Nav
}

/**
 * The single Start/Stop recording control, fixed just below the device identity. Idle: a prominent
 * accent "Start recording" button. Recording: a pulsing REC indicator + elapsed timer + Stop.
 */
@Composable
private fun RecordingControl(recording: Boolean, startMillis: Long, onStart: () -> Unit, onStop: () -> Unit) {
    val pal = LocalPalette.current
    if (recording) {
        var now by remember { mutableStateOf(startMillis) }
        LaunchedEffect(startMillis) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1_000)
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(pal.accent.copy(alpha = 0.12f))
                .border(1.dp, pal.accent.copy(alpha = 0.30f), RoundedCornerShape(16.dp))
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlowDot(color = pal.accent, pulse = true)
            Spacer(Modifier.width(10.dp))
            Text(
                text = "REC",
                style = MaterialTheme.typography.labelMedium.mono(),
                color = pal.accent,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = elapsedLabel((now - startMillis).coerceAtLeast(0)),
                style = MaterialTheme.typography.titleMedium.mono(),
                color = TextPrimary,
            )
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, pal.accent, RoundedCornerShape(12.dp))
                    .clickable(onClick = onStop)
                    .padding(horizontal = 22.dp, vertical = 9.dp),
            ) {
                Text(
                    text = "Stop",
                    style = MaterialTheme.typography.labelLarge,
                    color = pal.accent,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    } else {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(pal.accent)
                .clickable(onClick = onStart)
                .padding(vertical = 15.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "▶  Start recording",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = Color.Black,
            )
        }
    }
}

private fun elapsedLabel(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}

@Composable
private fun PortraitLayout(
    modifier: Modifier,
    info: DeviceInfo,
    metrics: LiveMetrics,
    session: BatterySession,
    history: List<Float>,
    charts: @Composable () -> Unit,
    recordingControl: @Composable () -> Unit,
    guard: @Composable () -> Unit,
    selectedTheme: ThemeId,
    onThemeChange: (ThemeId) -> Unit,
    onOpenHistory: () -> Unit,
    recording: Boolean,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(GAP),
    ) {
        Header(info.appVersion, selectedTheme, onThemeChange, onOpenHistory, recording)
        guard()
        IdentityHeader(info, Modifier.fillMaxWidth())
        recordingControl()
        CpuCard(metrics.cpu, history, Modifier.fillMaxWidth())
        if (metrics.cpu.hasPerCoreData) {
            CpuCoresCard(metrics.cpu, Modifier.fillMaxWidth())
        }
        RamCard(metrics, Modifier.fillMaxWidth())
        PowerThermalCard(metrics.battery, metrics.thermal, session, Modifier.fillMaxWidth(), showSession = recording)
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
    recordingControl: @Composable () -> Unit,
    guard: @Composable () -> Unit,
    selectedTheme: ThemeId,
    onThemeChange: (ThemeId) -> Unit,
    onOpenHistory: () -> Unit,
    recording: Boolean,
) {
    // Device specs live behind the identity "spec plate" instead of a permanent half-width column,
    // so the full width serves the live instrument content.
    var specsOpen by remember { mutableStateOf(false) }
    Column(modifier) {
        Header(info.appVersion, selectedTheme, onThemeChange, onOpenHistory, recording)
        Spacer(Modifier.height(GAP))
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(GAP),
        ) {
            guard()
            IdentityHeader(info, Modifier.fillMaxWidth(), compact = true, onClick = { specsOpen = true })
            recordingControl()
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
            PowerThermalCard(metrics.battery, metrics.thermal, session, Modifier.fillMaxWidth(), showSession = recording)
            charts()
            Spacer(Modifier.height(20.dp))
        }
    }
    if (specsOpen) SystemSpecsDialog(info) { specsOpen = false }
}

/** Whether the kernel exposed any per-core signal (clock or load) worth showing. */
private val CpuClock.hasPerCoreData: Boolean
    get() = perCoreMhz.isNotEmpty() || perCoreLoad.any { !it.isNaN() }

@Composable
private fun Header(
    appVersion: String,
    selectedTheme: ThemeId,
    onThemeChange: (ThemeId) -> Unit,
    onOpenHistory: () -> Unit,
    recording: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoreGlyph(diameter = 26.dp)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = "CoreWatch",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )
            Text(
                text = "v$appVersion",
                style = MaterialTheme.typography.labelSmall.mono(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.sp,
            )
        }
        Spacer(Modifier.weight(1f))
        HistoryClockButton(onOpenHistory)
        Spacer(Modifier.width(4.dp))
        AccentPicker(selectedTheme, onThemeChange)
        Spacer(Modifier.width(12.dp))
        GlowDot(color = LocalPalette.current.accent, pulse = true)
        Spacer(Modifier.width(8.dp))
        // Tiles are always live; the label calls out when a session is being recorded.
        Text(
            text = if (recording) "REC" else "LIVE · 1s",
            style = MaterialTheme.typography.labelMedium.mono(),
            color = if (recording) LocalPalette.current.accent else MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.sp,
            fontWeight = if (recording) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
