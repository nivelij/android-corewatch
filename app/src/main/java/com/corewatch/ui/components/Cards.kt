package com.corewatch.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.corewatch.monitor.BatteryInfo
import com.corewatch.monitor.ChargeStatus
import com.corewatch.monitor.CpuClock
import com.corewatch.monitor.DeviceInfo
import com.corewatch.monitor.LiveMetrics
import com.corewatch.monitor.Plug
import com.corewatch.ui.theme.Accent
import com.corewatch.ui.theme.AccentRamp
import com.corewatch.ui.theme.Panel
import com.corewatch.ui.theme.PanelBorder
import com.corewatch.ui.theme.StatusHot
import com.corewatch.ui.theme.StatusNormal
import com.corewatch.ui.theme.StatusWarm
import com.corewatch.ui.theme.TextPrimary
import com.corewatch.ui.theme.mono
import kotlin.math.abs
import kotlin.math.roundToInt

/* ---------- formatting ---------- */

private fun mhzToGhz(mhz: Int): String = String.format("%.2f", mhz / 1000f)
private fun bytesToGb(bytes: Long): String = String.format("%.1f", bytes / 1_073_741_824f)

/* ---------- core glyph (identity mark) ---------- */

/**
 * CoreWatch identity mark: a hex "chip" outline with a telemetry pulse cutting through it —
 * intentionally distinct from concentric-ring / target logos.
 */
@Composable
fun CoreGlyph(diameter: Dp, modifier: Modifier = Modifier) {
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
            brush = Brush.linearGradient(AccentRamp),
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
            color = Accent,
            style = Stroke(width = stroke * 0.9f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/* ---------- hero identity ---------- */

@Composable
fun IdentityHeader(info: DeviceInfo, modifier: Modifier = Modifier, compact: Boolean = false) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = Panel,
        border = BorderStroke(1.dp, Brush.linearGradient(AccentRamp.map { it.copy(alpha = 0.45f) })),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(if (compact) 14.dp else 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoreGlyph(diameter = if (compact) 44.dp else 60.dp)
            Spacer(Modifier.width(if (compact) 14.dp else 18.dp))
            Column {
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
                    color = Accent,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${info.abi} · ${info.cores} cores",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/* ---------- live tiles ---------- */

@Composable
fun CpuCard(cpu: CpuClock, history: List<Float>, modifier: Modifier = Modifier) {
    Panel(label = "CPU", modifier = modifier, trailing = { LiveBadge(cpu.live) }) {
        when {
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
                MetricValue(value = "—", unit = "GHz")
                Spacer(Modifier.height(2.dp))
                Caption("live clock not exposed by kernel")
            }
        }
    }
}

/** Per-core clock grid — one tile per logical CPU, tinted by how hard the core is boosting. */
@Composable
fun CpuCoresCard(cpu: CpuClock, modifier: Modifier = Modifier) {
    val perCore = cpu.perCoreMhz
    Panel(label = "CPU Cores", modifier = modifier, trailing = { LiveBadge(cpu.live) }) {
        if (perCore.isEmpty()) {
            Caption("per-core clocks not exposed by kernel")
            return@Panel
        }
        // Tint range: cluster min/max when known, else derive from the sample itself.
        val lo = cpu.minMhz ?: perCore.min()
        val hi = (cpu.maxMhz ?: perCore.max()).coerceAtLeast(lo + 1)
        perCore.chunked(CORE_COLUMNS).forEachIndexed { rowIdx, row ->
            if (rowIdx > 0) Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEachIndexed { colIdx, mhz ->
                    val coreNumber = rowIdx * CORE_COLUMNS + colIdx + 1
                    CoreTile(coreNumber, mhz, coreIntensity(mhz, lo, hi), Modifier.weight(1f))
                }
                // Keep tiles a uniform width by padding the final row.
                repeat(CORE_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private const val CORE_COLUMNS = 4

private fun coreIntensity(mhz: Int, lo: Int, hi: Int): Float =
    ((mhz - lo).toFloat() / (hi - lo)).coerceIn(0f, 1f)

@Composable
private fun CoreTile(core: Int, mhz: Int, intensity: Float, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    // Idle cores stay dim; boosting cores glow gold.
    val fill = Accent.copy(alpha = 0.10f + 0.42f * intensity)
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
        // Number and unit are stacked so 4-digit clocks (e.g. "3187") never clip the unit.
        Text(
            text = "$mhz",
            style = MaterialTheme.typography.titleMedium.mono().copy(fontWeight = FontWeight.SemiBold),
            color = TextPrimary,
            maxLines = 1,
        )
        Text(
            text = "MHz",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
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

@Composable
fun BatteryCard(battery: BatteryInfo, modifier: Modifier = Modifier) {
    val tempC = battery.tempC
    val status = tempC?.let { tempStatus(it) }
    val tempColor by animateColorAsState(
        targetValue = status?.color ?: MaterialTheme.colorScheme.onSurfaceVariant,
        label = "tempColor",
    )
    Panel(
        label = "Battery",
        modifier = modifier,
        // Subtle pulsing "power in" cue when charging; the full state is spelled out below.
        trailing = { if (battery.isCharging) GlowDot(color = Accent, pulse = true) },
    ) {
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
            val sign = if (ma > 0) "+" else if (ma < 0) "-" else ""
            Text(
                text = "$sign${formatCurrent(abs(ma))}",
                style = MaterialTheme.typography.titleMedium.mono(),
                color = if (battery.isCharging) Accent else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
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
        "Architecture" to info.abi,
        "Clock range" to clockRange,
        "Android" to "${info.androidRelease} · API ${info.sdkInt}",
        "Security patch" to (info.securityPatch ?: "—"),
        "Kernel" to (info.kernel ?: "—"),
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
                .background(Brush.horizontalGradient(AccentRamp)),
        )
    }
}
