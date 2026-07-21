package com.corewatch.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.corewatch.BatterySession
import com.corewatch.monitor.BatteryHealth
import com.corewatch.monitor.BatteryInfo
import com.corewatch.monitor.ChargeStatus
import com.corewatch.monitor.CpuClock
import com.corewatch.monitor.DeviceInfo
import com.corewatch.monitor.DiskInfo
import com.corewatch.monitor.LiveMetrics
import com.corewatch.monitor.Plug
import com.corewatch.monitor.ThermalInfo
import com.corewatch.monitor.ThermalStatus
import com.corewatch.ui.theme.LocalPalette
import com.corewatch.ui.theme.OledBlack
import com.corewatch.ui.theme.paletteFor
import com.corewatch.ui.theme.Panel
import com.corewatch.ui.theme.PanelBorder
import com.corewatch.ui.theme.StatusHot
import com.corewatch.ui.theme.StatusNormal
import com.corewatch.ui.theme.StatusWarm
import com.corewatch.ui.theme.TextMuted
import com.corewatch.ui.theme.TextPrimary
import com.corewatch.ui.theme.ThemeCatalog
import com.corewatch.ui.theme.ThemeId
import com.corewatch.ui.theme.mono
import kotlin.math.abs
import kotlin.math.roundToInt

/* ---------- formatting ---------- */

private fun mhzToGhz(mhz: Int): String = String.format("%.2f", mhz / 1000f)
private fun bytesToGb(bytes: Long): String = String.format("%.1f", bytes / 1_073_741_824f)
private fun mbps(bytesPerSec: Float): String = String.format("%.1f MB/s", bytesPerSec / 1_048_576f)

/* ---------- core glyph (identity mark) ---------- */

/**
 * CoreWatch identity mark: a hex "chip" outline with a telemetry pulse cutting through it —
 * intentionally distinct from concentric-ring / target logos.
 */
@Composable
fun CoreGlyph(diameter: Dp, modifier: Modifier = Modifier) {
    val pal = LocalPalette.current
    Canvas(modifier.size(diameter)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = size.minDimension * 0.46f
        val stroke = size.minDimension * 0.085f

        // Pointy-top hexagon.
        val hex = Path()
        listOf(-90, -30, 30, 90, 150, 210).forEachIndexed { i, deg ->
            val a = Math.toRadians(deg.toDouble())
            val x = cx + (r * cos(a)).toFloat()
            val y = cy + (r * sin(a)).toFloat()
            if (i == 0) hex.moveTo(x, y) else hex.lineTo(x, y)
        }
        hex.close()
        drawPath(
            path = hex,
            brush = Brush.linearGradient(pal.accentRamp),
            style = Stroke(width = stroke, join = StrokeJoin.Round),
        )

        // Pulse waveform through the middle.
        val wave = Path().apply {
            moveTo(cx - r * 0.60f, cy)
            lineTo(cx - r * 0.26f, cy)
            lineTo(cx - r * 0.06f, cy - r * 0.46f)
            lineTo(cx + r * 0.16f, cy + r * 0.50f)
            lineTo(cx + r * 0.34f, cy)
            lineTo(cx + r * 0.60f, cy)
        }
        drawPath(
            path = wave,
            color = pal.accent,
            style = Stroke(width = stroke * 0.9f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/* ---------- hero identity ---------- */

@Composable
fun IdentityHeader(
    info: DeviceInfo,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val pal = LocalPalette.current
    val clickable = if (onClick != null) modifier.clickable(onClick = onClick) else modifier
    Surface(
        modifier = clickable,
        shape = RoundedCornerShape(24.dp),
        color = Panel,
        border = BorderStroke(1.dp, Brush.linearGradient(pal.accentRamp.map { it.copy(alpha = 0.45f) })),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(if (compact) 14.dp else 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoreGlyph(diameter = if (compact) 44.dp else 60.dp)
            Spacer(Modifier.width(if (compact) 14.dp else 18.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = info.deviceName,
                    style = if (compact) MaterialTheme.typography.titleMedium
                    else MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    maxLines = 2,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = info.socLabel,
                    style = MaterialTheme.typography.titleSmall,
                    color = pal.accent,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${info.abi} · ${info.cores} cores",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // "Spec plate" cue: the identity card doubles as the door to the full device sheet.
            if (onClick != null) {
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "SPECS ›",
                    style = MaterialTheme.typography.labelMedium.mono(),
                    color = pal.accent,
                    letterSpacing = 1.sp,
                )
            }
        }
    }
}

/** The full device spec sheet, reached on demand by tapping the identity "spec plate". */
@Composable
fun SystemSpecsDialog(info: DeviceInfo, onDismiss: () -> Unit) {
    // Opt out of the narrow platform-default width so the two-column spec grid has room to breathe.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.62f),
            shape = RoundedCornerShape(24.dp),
            color = OledBlack,
            border = BorderStroke(1.dp, PanelBorder),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                SystemInfoPanel(info, Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Close",
                    style = MaterialTheme.typography.labelLarge,
                    color = LocalPalette.current.accent,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.End)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/* ---------- live tiles ---------- */

@Composable
fun CpuCard(cpu: CpuClock, history: List<Float>, modifier: Modifier = Modifier) {
    Panel(label = "CPU", modifier = modifier, trailing = { LiveBadge(cpu.live) }) {
        val load = cpu.overallLoad
        when {
            // Headline is overall utilization %, which moves with real activity regardless of any
            // clock cap; the clock is kept as context underneath.
            load != null -> {
                MetricValue(value = "${(load * 100).roundToInt()}", unit = "%", gradient = true)
                Spacer(Modifier.height(2.dp))
                Caption(clockCaption(cpu))
                Spacer(Modifier.height(14.dp))
                Sparkline(history, Modifier.fillMaxWidth().height(56.dp))
            }

            // Load not available yet (first sample) or /proc/stat blocked — fall back to peak clock.
            cpu.live && cpu.currentMaxMhz != null -> {
                MetricValue(value = mhzToGhz(cpu.currentMaxMhz), unit = "GHz", gradient = true)
                Spacer(Modifier.height(2.dp))
                Caption("peak across ${cpu.cores} cores")
                Spacer(Modifier.height(14.dp))
                Sparkline(history, Modifier.fillMaxWidth().height(56.dp))
            }

            cpu.maxMhz != null -> {
                MetricValue(value = mhzToGhz(cpu.maxMhz), unit = "GHz")
                Spacer(Modifier.height(2.dp))
                Caption("max clock · ${cpu.cores} cores")
                if (cpu.minMhz != null) {
                    Caption("range ${mhzToGhz(cpu.minMhz)}–${mhzToGhz(cpu.maxMhz)} GHz")
                }
            }

            else -> {
                MetricValue(value = "—", unit = "%")
                Spacer(Modifier.height(2.dp))
                Caption("live load not exposed by kernel")
            }
        }
    }
}

/** Clock context shown under the CPU load headline, e.g. "avg 1.50 · peak 1.84 GHz". */
private fun clockCaption(cpu: CpuClock): String {
    val avg = cpu.avgMhz
    val peak = cpu.currentMaxMhz
    return when {
        avg != null && peak != null -> "avg ${mhzToGhz(avg)} · peak ${mhzToGhz(peak)} GHz"
        peak != null -> "peak ${mhzToGhz(peak)} GHz"
        else -> "${cpu.cores} cores"
    }
}

/** Per-core grid — one tile per logical CPU, showing clock and/or live load %, tinted by load. */
@Composable
fun CpuCoresCard(cpu: CpuClock, modifier: Modifier = Modifier) {
    val mhzList = cpu.perCoreMhz
    val loadList = cpu.perCoreLoad
    val count = maxOf(mhzList.size, loadList.size)
    Panel(label = "CPU Cores", modifier = modifier, trailing = { LiveBadge(cpu.live) }) {
        if (count == 0) {
            Caption("per-core data not exposed by kernel")
            return@Panel
        }
        // Clock tint range: cluster min/max when known, else derive from the sample itself.
        val lo = cpu.minMhz ?: mhzList.minOrNull() ?: 0
        val hi = (cpu.maxMhz ?: mhzList.maxOrNull() ?: (lo + 1)).coerceAtLeast(lo + 1)
        (0 until count).toList().chunked(CORE_COLUMNS).forEachIndexed { rowIdx, rowIndices ->
            if (rowIdx > 0) Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rowIndices.forEach { i ->
                    val mhz = mhzList.getOrNull(i)
                    val load = loadList.getOrNull(i)?.takeIf { !it.isNaN() }
                    // Prefer load for the tile tint; fall back to relative clock when load is absent.
                    val tint = load ?: mhz?.let { coreIntensity(it, lo, hi) } ?: 0f
                    CoreTile(i + 1, mhz, load, tint, Modifier.weight(1f))
                }
                // Keep tiles a uniform width by padding the final row.
                repeat(CORE_COLUMNS - rowIndices.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private const val CORE_COLUMNS = 4

private fun coreIntensity(mhz: Int, lo: Int, hi: Int): Float =
    ((mhz - lo).toFloat() / (hi - lo)).coerceIn(0f, 1f)

@Composable
private fun CoreTile(core: Int, mhz: Int?, load: Float?, tint: Float, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    val accent = LocalPalette.current.accent
    // Idle cores stay dim; busy cores glow in the active accent.
    val fill = accent.copy(alpha = 0.10f + 0.42f * tint.coerceIn(0f, 1f))
    Column(
        modifier = modifier
            .clip(shape)
            .background(fill)
            .border(BorderStroke(1.dp, PanelBorder), shape)
            .padding(vertical = 10.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Core $core",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
        // Load % is the hero (it moves even when a core's clock is pinned at a capped ceiling); the
        // clock falls back only when load is unavailable. Unit is stacked so it never clips.
        Text(
            text = load?.let { "${(it * 100).roundToInt()}" } ?: mhz?.toString() ?: "—",
            style = MaterialTheme.typography.titleMedium.mono().copy(fontWeight = FontWeight.SemiBold),
            color = TextPrimary,
            maxLines = 1,
        )
        Text(
            text = if (load != null) "%" else "MHz",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        if (load != null) {
            Spacer(Modifier.height(7.dp))
            LoadBar(load, accent)
            if (mhz != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "$mhz MHz",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Thin per-core load meter. */
@Composable
private fun LoadBar(fraction: Float, accent: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(CircleShape)
            .background(PanelBorder),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .clip(CircleShape)
                .background(accent),
        )
    }
}

@Composable
fun RamCard(metrics: LiveMetrics, modifier: Modifier = Modifier) {
    val total = metrics.ramTotalBytes
    val fraction = if (total > 0) (metrics.ramUsedBytes.toFloat() / total).coerceIn(0f, 1f) else 0f
    val animated by animateFloatAsState(fraction, tween(600), label = "ram")

    Panel(label = "Memory", modifier = modifier) {
        MetricValue(value = bytesToGb(metrics.ramUsedBytes), unit = "GB")
        Spacer(Modifier.height(2.dp))
        Caption("of ${bytesToGb(total)} GB · ${(fraction * 100).roundToInt()}%")
        Spacer(Modifier.height(14.dp))
        GradientBar(fraction = animated)
    }
}

/**
 * Storage instrument: one capacity block per mounted volume (Internal + microSD), and — only where
 * the kernel exposes /proc/diskstats to the app — a live read/write throughput line per volume kind
 * below a hairline. When throughput is blocked (stock Android) the card silently shows capacity only,
 * the same degrade as [CpuCard] falling back from load to clock.
 */
@Composable
fun StorageCard(disk: DiskInfo, modifier: Modifier = Modifier) {
    val accent = LocalPalette.current.accent
    Panel(label = "Storage", modifier = modifier) {
        if (disk.volumes.isEmpty()) {
            Caption("storage not readable")
            return@Panel
        }
        disk.volumes.forEachIndexed { i, v ->
            if (i > 0) Spacer(Modifier.height(16.dp))
            val fraction = if (v.totalBytes > 0) (v.usedBytes.toFloat() / v.totalBytes).coerceIn(0f, 1f) else 0f
            val animated by animateFloatAsState(fraction, tween(600), label = "vol$i")
            Text(
                text = v.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.sp,
            )
            Spacer(Modifier.height(2.dp))
            MetricValue(value = bytesToGb(v.usedBytes), unit = "GB")
            Spacer(Modifier.height(2.dp))
            Caption("of ${bytesToGb(v.totalBytes)} GB · ${(fraction * 100).roundToInt()}%")
            Spacer(Modifier.height(10.dp))
            GradientBar(fraction = animated)
        }
        // Throughput only where the counters are readable and a rate exists (not the first sample).
        if (disk.ioSupported && disk.io.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            HairlineDivider()
            Spacer(Modifier.height(12.dp))
            SectionLabel("Throughput")
            Spacer(Modifier.height(10.dp))
            disk.io.forEachIndexed { i, rate ->
                if (i > 0) Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = rate.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "R ${mbps(rate.readBytesPerSec)}  ·  W ${mbps(rate.writeBytesPerSec)}",
                        style = MaterialTheme.typography.titleMedium.mono(),
                        color = accent,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private enum class TempStatus(val label: String, val color: Color) {
    NORMAL("Normal", StatusNormal),
    WARM("Warm", StatusWarm),
    HOT("Hot", StatusHot),
}

private fun tempStatus(tempC: Float): TempStatus = when {
    tempC < 35f -> TempStatus.NORMAL
    tempC <= 40f -> TempStatus.WARM
    else -> TempStatus.HOT
}

/**
 * The battery is the app's main instrument, so it carries everything power/thermal in one cluster:
 * live battery (temp + charge + current/power) on the left, thermal throttle state on the right,
 * and a hairline-divided SESSION footer with the whole-session min/max/avg readout below.
 */
@Composable
fun PowerThermalCard(
    battery: BatteryInfo,
    thermal: ThermalInfo,
    session: BatterySession,
    modifier: Modifier = Modifier,
    // The live readings above always show; the per-session aggregates only make sense while recording.
    showSession: Boolean = true,
) {
    val accent = LocalPalette.current.accent
    val tempC = battery.tempC
    val tempColor by animateColorAsState(
        targetValue = tempC?.let { tempStatus(it).color } ?: MaterialTheme.colorScheme.onSurfaceVariant,
        label = "battTemp",
    )
    val (thermalLabel, thermalColor) = thermalDisplay(thermal.status)

    Panel(
        label = "Power & thermal",
        modifier = modifier,
        trailing = { if (battery.isCharging) GlowDot(color = accent, pulse = true) },
    ) {
        Row(Modifier.fillMaxWidth()) {
            // Left: live battery (a touch wider so the current · power line stays on one line).
            Column(Modifier.weight(1.25f)) {
                MetricValue(
                    value = tempC?.let { String.format("%.1f", it) } ?: "—",
                    unit = "°C",
                    color = tempColor,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = statusLine(battery),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                battery.currentMa?.let { ma ->
                    Spacer(Modifier.height(3.dp))
                    // On battery: discharge current + positive draw. Plugged in: charge current (+),
                    // and draw reads n/a since the battery isn't the thing being consumed.
                    val text = buildString {
                        if (battery.onBattery) {
                            append(formatCurrent(abs(ma)))
                            append(" · ").append(battery.drawW?.let { String.format("%.2f W", it) } ?: "—")
                        } else {
                            append("+").append(formatCurrent(abs(ma))).append(" · n/a")
                        }
                    }
                    Text(
                        text = text,
                        style = MaterialTheme.typography.titleMedium.mono(),
                        color = if (battery.isCharging) accent else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            // Right: thermal throttle state.
            Column(Modifier.weight(1f)) {
                Text(
                    text = thermalLabel,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = thermalColor,
                    maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                Caption("throttle state")
                thermal.headroom?.let { hr ->
                    Spacer(Modifier.height(10.dp))
                    val animated by animateFloatAsState(hr.coerceIn(0f, 1f), tween(600), label = "headroom")
                    ThermalBar(animated, thermalColor)
                    Spacer(Modifier.height(5.dp))
                    // 0% = cool, 100% = throttling begins; the bar fills as the device heats up.
                    Caption("thermal load ${(hr * 100).roundToInt()}%")
                }
            }
        }

        if (showSession) {
            Spacer(Modifier.height(14.dp))
            HairlineDivider()
            Spacer(Modifier.height(12.dp))
            SectionLabel("Session")
            Spacer(Modifier.height(10.dp))
            SessionStat3(
                "Peak" to (session.maxTempC?.let { String.format("%.1f °C", it) } ?: "—"),
                "Low" to (session.minTempC?.let { String.format("%.1f °C", it) } ?: "—"),
                "Avg" to (session.avgPowerW?.let { String.format("%.2f W", it) } ?: "—"),
            )
            SessionStat3(
                "Energy" to formatEnergy(session.energyMwh),
                "Health" to healthLabel(battery.health),
                null,
            )
        }
    }
}

/** A row of up to three compact label/value session cells. */
@Composable
private fun SessionStat3(a: Pair<String, String>, b: Pair<String, String>?, c: Pair<String, String>?) {
    Row(Modifier.fillMaxWidth()) {
        InfoCell(a.first, a.second, Modifier.weight(1f))
        if (b != null) InfoCell(b.first, b.second, Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
        if (c != null) InfoCell(c.first, c.second, Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun HairlineDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(PanelBorder))
}

private fun statusLine(b: BatteryInfo): String {
    val state = when (b.status) {
        ChargeStatus.CHARGING -> when (b.plug) {
            Plug.AC -> "AC charging"
            Plug.USB -> "USB charging"
            Plug.WIRELESS -> "Wireless charging"
            Plug.NONE -> "Charging"
        }
        ChargeStatus.FULL -> "Full"
        ChargeStatus.DISCHARGING -> "On battery"
        ChargeStatus.NOT_CHARGING -> "Not charging"
        ChargeStatus.UNKNOWN -> "—"
    }
    return b.levelPct?.let { "$it% · $state" } ?: state
}

private fun formatCurrent(maAbs: Int): String =
    if (maAbs >= 1000) String.format("%.2f A", maAbs / 1000f) else "$maAbs mA"

/* ---------- thermal / session helpers ---------- */

private fun thermalDisplay(status: ThermalStatus): Pair<String, Color> = when (status) {
    ThermalStatus.NONE -> "Normal" to StatusNormal
    ThermalStatus.LIGHT -> "Light" to StatusWarm
    ThermalStatus.MODERATE -> "Moderate" to StatusWarm
    ThermalStatus.SEVERE -> "Severe" to StatusHot
    ThermalStatus.CRITICAL -> "Critical" to StatusHot
    ThermalStatus.EMERGENCY -> "Emergency" to StatusHot
    ThermalStatus.SHUTDOWN -> "Shutdown" to StatusHot
    ThermalStatus.UNKNOWN -> "—" to TextMuted
}

@Composable
private fun ThermalBar(fraction: Float, color: Color) {
    Box(
        Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(PanelBorder),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .clip(CircleShape)
                .background(color),
        )
    }
}

private fun formatEnergy(mwh: Float): String = when {
    mwh <= 0f -> "—"
    mwh >= 1000f -> String.format("%.2f Wh", mwh / 1000f)
    else -> "${mwh.roundToInt()} mWh"
}

private fun healthLabel(h: BatteryHealth): String = when (h) {
    BatteryHealth.GOOD -> "Good"
    BatteryHealth.OVERHEAT -> "Overheat"
    BatteryHealth.DEAD -> "Dead"
    BatteryHealth.OVER_VOLTAGE -> "Over-voltage"
    BatteryHealth.COLD -> "Cold"
    BatteryHealth.FAILURE -> "Failure"
    BatteryHealth.UNKNOWN -> "—"
}

/* ---------- static system info grid ---------- */

@Composable
fun SystemInfoPanel(info: DeviceInfo, modifier: Modifier = Modifier) {
    val clockRange = when {
        info.minClockMhz != null && info.maxClockMhz != null ->
            "${mhzToGhz(info.minClockMhz)}–${mhzToGhz(info.maxClockMhz)} GHz"
        info.maxClockMhz != null -> "up to ${mhzToGhz(info.maxClockMhz)} GHz"
        else -> "—"
    }
    val items = listOf(
        "Device" to info.deviceName,
        "Manufacturer" to info.manufacturer,
        "Chipset" to info.socLabel,
        "SoC vendor" to (info.socManufacturer ?: "—"),
        "SoC model" to (info.socModelRaw ?: "—"),
        "CPU cores" to info.cores.toString(),
        "GPU" to (info.gpuRenderer ?: "—"),
        "Architecture" to info.abi,
        "Clock range" to clockRange,
        "Android" to "${info.androidRelease} · API ${info.sdkInt}",
        "Security patch" to (info.securityPatch ?: "—"),
        "Kernel" to (info.kernel ?: "—"),
        "App version" to "CoreWatch ${info.appVersion}",
    )

    Panel(label = "System", modifier = modifier) {
        items.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                InfoCell(row[0].first, row[0].second, Modifier.weight(1f))
                if (row.size > 1) {
                    InfoCell(row[1].first, row[1].second, Modifier.weight(1f))
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/* ---------- small building blocks ---------- */

@Composable
private fun Caption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun GradientBar(fraction: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(CircleShape)
            .background(PanelBorder),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .clip(CircleShape)
                .background(Brush.horizontalGradient(LocalPalette.current.accentRamp)),
        )
    }
}

/* ---------- background-capture guard banner ---------- */

/**
 * Shown only while CoreWatch is NOT on the battery-optimization allowlist: an instructional prompt
 * telling the user why background capture can be cut short and exactly what to do about it. Tapping
 * "Allow" opens the system dialog; the banner self-hides once the exemption is granted.
 */
@Composable
fun BackgroundGuardBanner(
    onAllow: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(Panel)
            .border(BorderStroke(1.dp, StatusWarm.copy(alpha = 0.40f)), shape)
            .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 5.dp).size(8.dp).clip(CircleShape).background(StatusWarm))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "Keep capturing during games",
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "Android can stop CoreWatch when a game needs memory, cutting your " +
                    "session short. Tap Allow, then pick “Allow” / “Don’t optimize” so it can " +
                    "keep recording in the background.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Allow",
                    style = MaterialTheme.typography.labelLarge,
                    color = LocalPalette.current.accent,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onAllow)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "Not now",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextMuted,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/* ---------- accent picker (header chip + popup) ---------- */

/**
 * The header accent control: a filled hexagon "chip" in the current accent that opens a compact
 * popup of the available accents. The hexagon echoes CoreWatch's chip glyph — each theme reads as
 * a chip you slot in, not a generic colour swatch.
 */
@Composable
fun AccentPicker(selected: ThemeId, onSelect: (ThemeId) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val current = paletteFor(selected)
    Box(modifier) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { open = true }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HexSwatch(current.accentRamp, size = 20.dp, bright = true)
            Spacer(Modifier.width(3.dp))
            Text(
                text = "▾",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                SectionLabel("Accent")
                Spacer(Modifier.height(12.dp))
                // Every accent in a single row (scrolls horizontally if it ever exceeds the width),
                // so there's no wrapping and every swatch stays uniform.
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ThemeCatalog.forEach { p ->
                        val isSel = p.id == selected
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSel) p.accent.copy(alpha = 0.14f) else Color.Transparent)
                                .clickable { onSelect(p.id); open = false }
                                .padding(vertical = 6.dp, horizontal = 6.dp),
                        ) {
                            HexSwatch(p.accentRamp, size = 32.dp, bright = isSel)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = p.displayName,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSel) TextPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A hexagon accent chip. Every swatch is the exact same solid gradient hexagon — no strokes that
 * would change its footprint — so all render at an identical size; [bright] only controls full vs
 * dimmed fill. Selection is shown by the cell's background chip, not by enlarging the hexagon.
 */
@Composable
private fun HexSwatch(
    ramp: List<Color>,
    size: Dp,
    bright: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.size(size)) {
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val r = this.size.minDimension * 0.44f
        val hex = hexPath(cx, cy, r)
        drawPath(hex, brush = Brush.linearGradient(ramp), alpha = if (bright) 1f else 0.42f)
    }
}

/** Pointy-top hexagon, matching the CoreWatch logo glyph. */
private fun hexPath(cx: Float, cy: Float, r: Float): Path {
    val path = Path()
    intArrayOf(-90, -30, 30, 90, 150, 210).forEachIndexed { i, deg ->
        val a = Math.toRadians(deg.toDouble())
        val x = cx + (r * cos(a)).toFloat()
        val y = cy + (r * sin(a)).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}
