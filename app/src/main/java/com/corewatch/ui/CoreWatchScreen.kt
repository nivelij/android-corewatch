package com.corewatch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.corewatch.MonitorViewModel
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
import com.corewatch.ui.components.BatteryCard
import com.corewatch.ui.components.SystemInfoPanel
import com.corewatch.ui.components.AccentPicker
import com.corewatch.ui.theme.LocalPalette
import com.corewatch.ui.theme.TextPrimary
import com.corewatch.ui.theme.ThemeId
import com.corewatch.ui.theme.mono

private const val HISTORY_SIZE = 60
private val GAP = 14.dp

@Composable
fun CoreWatchScreen(
    viewModel: MonitorViewModel = viewModel(),
    selectedTheme: ThemeId = LocalPalette.current.id,
    onThemeChange: (ThemeId) -> Unit = {},
) {
    val metrics by viewModel.metrics.collectAsStateWithLifecycle()
    val info = viewModel.deviceInfo

    // Rolling window of recent CPU peak frequencies, feeding the sparkline.
    val history = remember { mutableStateListOf<Float>() }
    LaunchedEffect(metrics) {
        metrics.cpu.currentMaxMhz?.let { mhz ->
            history.add(mhz.toFloat())
            if (history.size > HISTORY_SIZE) history.removeAt(0)
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
                intervalSec = viewModel.historyIntervalSec,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (wide) {
            LandscapeLayout(outer, info, metrics, history, charts, selectedTheme, onThemeChange)
        } else {
            PortraitLayout(outer, info, metrics, history, charts, selectedTheme, onThemeChange)
        }
    }
}

@Composable
private fun PortraitLayout(
    modifier: Modifier,
    info: DeviceInfo,
    metrics: LiveMetrics,
    history: List<Float>,
    charts: @Composable () -> Unit,
    selectedTheme: ThemeId,
    onThemeChange: (ThemeId) -> Unit,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(GAP),
    ) {
        Header(selectedTheme, onThemeChange)
        IdentityHeader(info, Modifier.fillMaxWidth())
        CpuCard(metrics.cpu, history, Modifier.fillMaxWidth())
        if (metrics.cpu.perCoreMhz.isNotEmpty()) {
            CpuCoresCard(metrics.cpu, Modifier.fillMaxWidth())
        }
        LiveDuo(metrics)
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
    history: List<Float>,
    charts: @Composable () -> Unit,
    selectedTheme: ThemeId,
    onThemeChange: (ThemeId) -> Unit,
) {
    Column(modifier) {
        Header(selectedTheme, onThemeChange)
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
                    BatteryCard(metrics.battery, Modifier.weight(1f).fillMaxHeight())
                }
                if (metrics.cpu.perCoreMhz.isNotEmpty()) {
                    CpuCoresCard(metrics.cpu, Modifier.fillMaxWidth())
                }
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

/** The RAM + battery-temp tiles as an equal-height pair. */
@Composable
private fun ColumnScope.LiveDuo(metrics: LiveMetrics) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(GAP),
    ) {
        RamCard(metrics, Modifier.weight(1f).fillMaxHeight())
        BatteryCard(metrics.battery, Modifier.weight(1f).fillMaxHeight())
    }
}

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
