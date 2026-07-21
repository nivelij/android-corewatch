package com.corewatch.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.corewatch.HistorySession
import com.corewatch.HistorySummary
import com.corewatch.MonitorViewModel
import com.corewatch.ui.components.HistoryCharts
import com.corewatch.ui.components.Panel
import com.corewatch.ui.theme.LocalPalette
import com.corewatch.ui.theme.Panel as PanelColor
import com.corewatch.ui.theme.PanelBorder
import com.corewatch.ui.theme.TextPrimary
import com.corewatch.ui.theme.mono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val HM = DateTimeFormatter.ofPattern("HH:mm")
private val DAY = DateTimeFormatter.ofPattern("EEE d MMM")
private val DAY_FULL = DateTimeFormatter.ofPattern("EEEE d MMM")

/** The completed-session log: sessions grouped under day headers, newest first. */
@Composable
fun HistoryScreen(
    viewModel: MonitorViewModel,
    onOpenSession: (Long) -> Unit,
    onBack: () -> Unit,
) {
    // Entries are written only on an explicit stop (which ends the app), so within a running app the
    // list is fixed — load it once, off the main thread.
    val summaries by produceState<List<HistorySummary>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { viewModel.historySummaries() }
    }

    ScreenScaffold(title = "History", onBack = onBack) {
        val list = summaries
        when {
            list == null -> Unit // brief first-frame flash; disk read is near-instant
            list.isEmpty() -> EmptyHistory()
            else -> {
                val zone = ZoneId.systemDefault()
                val today = LocalDate.now(zone)
                list.groupBy { Instant.ofEpochMilli(it.startEpochMillis).atZone(zone).toLocalDate() }
                    .entries.sortedByDescending { it.key }
                    .forEach { (date, sessions) ->
                        Text(
                            text = dayLabel(date, today),
                            style = MaterialTheme.typography.labelMedium,
                            color = LocalPalette.current.accent,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.5.sp,
                            modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp),
                        )
                        sessions.forEach { s ->
                            SessionRow(s, onClick = { onOpenSession(s.id) })
                        }
                    }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** One session in the log: when it ran + a few readouts. Tapping opens its charts. */
@Composable
private fun SessionRow(s: HistorySummary, onClick: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(s.startEpochMillis).atZone(zone).toLocalTime().format(HM)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(PanelColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = start,
                    style = MaterialTheme.typography.titleMedium.mono(),
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = duration(s.durationSec),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Readout("peak", s.peakTempC?.let { "%.0f°".format(it) } ?: "—")
            Readout("energy", "%.1f Wh".format(s.energyMwh / 1000f))
            Readout("avg", s.avgPowerW?.let { "%.1f W".format(it) } ?: "—")
            Spacer(Modifier.size(8.dp))
            Chevron()
        }
    }
}

@Composable
private fun Readout(label: String, value: String) {
    Column(
        horizontalAlignment = Alignment.End,
        modifier = Modifier.padding(start = 14.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.mono(),
            color = LocalPalette.current.accent,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A single archived session's four charts, with its span and battery aggregates. */
@Composable
fun HistoryDetailScreen(
    viewModel: MonitorViewModel,
    sessionId: Long,
    onBack: () -> Unit,
) {
    val session by produceState<HistorySession?>(initialValue = null, sessionId) {
        value = withContext(Dispatchers.IO) { viewModel.loadHistorySession(sessionId) }
    }
    val zone = ZoneId.systemDefault()
    val s = session
    val date = s?.let { Instant.ofEpochMilli(it.startEpochMillis).atZone(zone).toLocalDate().format(DAY_FULL) }

    ScreenScaffold(title = date ?: "Session", onBack = onBack) {
        if (s == null) {
            Spacer(Modifier.height(40.dp))
            Text(
                "Couldn’t load this session.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            return@ScreenScaffold
        }
        val from = Instant.ofEpochMilli(s.startEpochMillis).atZone(zone).toLocalTime().format(HM)
        val to = Instant.ofEpochMilli(s.endEpochMillis).atZone(zone).toLocalTime().format(HM)
        Text(
            text = "$from–$to · ${duration(s.durationSec)}",
            style = MaterialTheme.typography.bodyMedium.mono(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        SessionAggregates(s)
        Spacer(Modifier.height(14.dp))
        HistoryCharts(
            cpuPoints = s.snapshot.cpu,
            cpuIsLoad = viewModel.cpuLoadSupported,
            cpuMaxMhz = s.cpuMaxMhz,
            ramPoints = s.snapshot.ram,
            ramTotalBytes = s.snapshot.ramTotalBytes,
            tempPoints = s.snapshot.temp,
            powerPoints = s.snapshot.power,
            diskReadPoints = s.snapshot.diskRead,
            diskWritePoints = s.snapshot.diskWrite,
            // Derive from the archived data, not the live probe: show disk charts iff this session
            // actually captured throughput (a session recorded on a device that blocked it is all-NaN).
            showDiskIo = s.snapshot.diskRead.any { !it.isNaN() },
            gaps = s.snapshot.gaps,
            intervalSec = s.snapshot.historyIntervalSec,
            modifier = Modifier.fillMaxWidth(),
            emptyLabel = "no data",
        )
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun SessionAggregates(s: HistorySession) {
    val snap = s.snapshot
    val avg = if (snap.battElapsedSec > 0) (snap.battEnergyMwh / 1000f) / (snap.battElapsedSec / 3600f) else null
    Panel(label = "Session") {
        Row(Modifier.fillMaxWidth()) {
            Stat("peak", snap.battMaxTempC?.let { "%.1f °C".format(it) } ?: "—", Modifier.weight(1f))
            Stat("low", snap.battMinTempC?.let { "%.1f °C".format(it) } ?: "—", Modifier.weight(1f))
            Stat("energy", "%.0f mWh".format(snap.battEnergyMwh), Modifier.weight(1f))
            Stat("avg draw", avg?.let { "%.2f W".format(it) } ?: "—", Modifier.weight(1f))
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.sp,
        )
        Spacer(Modifier.size(3.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.mono(),
            color = LocalPalette.current.accent,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun EmptyHistory() {
    Column(
        Modifier.fillMaxWidth().padding(top = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "No recorded sessions yet",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            "CoreWatch saves a session each time you stop monitoring. Runs under 2 minutes aren’t kept.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

/** Shared chrome: gradient background, system-bar padding, a back control + screen title. */
@Composable
private fun ScreenScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    val pal = LocalPalette.current
    Box(
        Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(pal.bgTop, pal.bgBottom))),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton(onBack)
                Spacer(Modifier.size(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                )
            }
            content()
        }
    }
}

@Composable
private fun BackButton(onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(PanelColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(16.dp)) {
            val w = size.width; val h = size.height
            drawLine(color, Offset(w * 0.6f, h * 0.2f), Offset(w * 0.3f, h * 0.5f), 2.dp.toPx(), StrokeCap.Round)
            drawLine(color, Offset(w * 0.3f, h * 0.5f), Offset(w * 0.6f, h * 0.8f), 2.dp.toPx(), StrokeCap.Round)
        }
    }
}

@Composable
private fun Chevron() {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(12.dp)) {
        val w = size.width; val h = size.height
        drawLine(color, Offset(w * 0.35f, h * 0.2f), Offset(w * 0.65f, h * 0.5f), 1.5.dp.toPx(), StrokeCap.Round)
        drawLine(color, Offset(w * 0.65f, h * 0.5f), Offset(w * 0.35f, h * 0.8f), 1.5.dp.toPx(), StrokeCap.Round)
    }
}

/**
 * Header entry point into History: a "rewind" clock — a ring broken at the upper-left by a
 * counterclockwise arrowhead, with clock hands inside — painted in the active theme accent on a
 * faint accent chip so it reads as a live, on-brand control rather than a dull grey clock.
 */
@Composable
fun HistoryClockButton(onClick: () -> Unit) {
    val accent = LocalPalette.current.accent
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.14f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(19.dp)) {
            val stroke = 1.8.dp.toPx()
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = size.minDimension / 2f - stroke
            fun rad(deg: Double) = (deg * Math.PI / 180.0)
            fun onRing(deg: Double) = Offset((c.x + r * kotlin.math.cos(rad(deg))).toFloat(), (c.y + r * kotlin.math.sin(rad(deg))).toFloat())

            // Ring broken at the upper-left, sweeping clockwise from ~9-o'clock the long way round.
            val endDeg = 195.0
            drawArc(
                color = accent,
                startAngle = 250f,
                sweepAngle = 305f,
                useCenter = false,
                topLeft = Offset(c.x - r, c.y - r),
                size = Size(r * 2f, r * 2f),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            // Counterclockwise arrowhead at the open end.
            val tip = onRing(endDeg)
            val tangent = rad(endDeg + 90.0) // ccw tangent
            val tx = kotlin.math.cos(tangent).toFloat(); val ty = kotlin.math.sin(tangent).toFloat()
            val px = -ty; val py = tx
            val hl = 3.4.dp.toPx()
            drawPath(
                Path().apply {
                    moveTo(tip.x + tx * hl, tip.y + ty * hl)
                    lineTo(tip.x + px * hl * 0.85f, tip.y + py * hl * 0.85f)
                    lineTo(tip.x - px * hl * 0.85f, tip.y - py * hl * 0.85f)
                    close()
                },
                color = accent,
            )
            // Hands: 12 and ~4 o'clock.
            drawLine(accent, c, Offset(c.x, c.y - r * 0.5f), stroke, StrokeCap.Round)
            drawLine(accent, c, Offset(c.x + r * 0.4f, c.y + r * 0.2f), stroke, StrokeCap.Round)
        }
    }
}

// ---- formatting ----

private fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> date.format(DAY)
}

private fun duration(sec: Int): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m"
        else -> "${sec}s"
    }
}
