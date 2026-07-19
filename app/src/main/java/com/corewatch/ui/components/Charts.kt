package com.corewatch.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.corewatch.ui.theme.LocalPalette
import com.corewatch.ui.theme.Panel
import com.corewatch.ui.theme.PanelBorder
import com.corewatch.ui.theme.StatusWarm
import com.corewatch.ui.theme.mono

private fun percent(v: Float): String = "${v.roundToInt()}%"
private fun mhzToGhz(mhz: Float): String = String.format("%.2f GHz", mhz / 1000f)
private fun bytesToGb(bytes: Float): String = String.format("%.1f GB", bytes / 1_073_741_824f)
private fun degC(t: Float): String = String.format("%.1f °C", t)
private fun watts(w: Float): String = String.format("%.2f W", w) // draw, ≥0 (N/A while charging)

/** Both session charts, stacked. Renders whatever data exists so far this session. */
@Composable
fun HistoryCharts(
    cpuPoints: List<Float>,
    // CPU series holds load % when true (axis 0–100 %), else peak clock in MHz (axis 0–cpuMaxMhz,
    // shown in GHz). Decided by whether the OS exposes /proc/stat to the app on this device.
    cpuIsLoad: Boolean,
    cpuMaxMhz: Int?,
    ramPoints: List<Float>,
    ramTotalBytes: Long,
    tempPoints: List<Float>,
    powerPoints: List<Float>,
    gaps: List<Int>,
    intervalSec: Int,
    modifier: Modifier = Modifier,
    // Live dashboard is still filling up ("collecting data…"); a finished archived session that
    // never captured a metric simply has none ("no data").
    emptyLabel: String = "collecting data…",
) {
    val cpuCard: @Composable (Modifier) -> Unit = if (cpuIsLoad) {
        { m -> MetricChartCard("CPU load", cpuPoints, 0f, 100f, gaps, intervalSec, ::percent, m, emptyLabel = emptyLabel) }
    } else {
        val cpuTop = cpuMaxMhz?.toFloat()
            ?: cpuPoints.filter { !it.isNaN() }.maxOrNull()?.times(1.1f)
            ?: 1f
        { m -> MetricChartCard("CPU clock", cpuPoints, 0f, cpuTop, gaps, intervalSec, ::mhzToGhz, m, emptyLabel = emptyLabel) }
    }
    val ramTop = if (ramTotalBytes > 0) ramTotalBytes.toFloat()
    else ramPoints.maxOrNull()?.times(1.1f) ?: 1f

    // Temperature sits in a fixed 20–50°C band (so slow drift reads honestly, not flat or jumpy),
    // expanding only if a reading falls outside it.
    val tempValid = tempPoints.filter { !it.isNaN() }
    val tempLo = minOf(TEMP_BAND_LOW, tempValid.minOrNull() ?: TEMP_BAND_LOW)
    val tempHi = maxOf(TEMP_BAND_HIGH, tempValid.maxOrNull() ?: TEMP_BAND_HIGH)

    // Power draw is a positive-only consumption metric (NaN while charging → drawn as a break).
    // Anchor the axis at 0 so higher on the chart = higher draw; pad a flat trace off the floor.
    val powerValid = powerPoints.filter { !it.isNaN() }
    val powerLo = 0f
    var powerHi = powerValid.maxOrNull() ?: 1f
    if (powerHi - powerLo < 1f) powerHi = powerLo + 1f

    // Wired once, placed into a 2×2 grid on wide screens (landscape) or a single column otherwise.
    val cards = listOf<@Composable (Modifier) -> Unit>(
        cpuCard,
        { m -> MetricChartCard("Memory history", ramPoints, 0f, ramTop, gaps, intervalSec, ::bytesToGb, m, emptyLabel = emptyLabel) },
        { m -> MetricChartCard("Battery temperature", tempPoints, tempLo, tempHi, gaps, intervalSec, ::degC, m, emptyLabel = emptyLabel) },
        { m -> MetricChartCard("Power draw", powerPoints, powerLo, powerHi, gaps, intervalSec, ::watts, m, naLabel = "n/a", emptyLabel = emptyLabel) },
    )

    // 2×2 grid only when there's room (landscape / tablets); phone portrait stacks one chart per
    // row so each stays wide enough to read.
    BoxWithConstraints(modifier) {
        if (maxWidth >= GRID_MIN_WIDTH) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                cards.chunked(2).forEach { rowCards ->
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        rowCards.forEach { card -> card(Modifier.weight(1f)) }
                        if (rowCards.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                cards.forEach { card -> card(Modifier.fillMaxWidth()) }
            }
        }
    }
}

private const val TEMP_BAND_LOW = 20f
private const val TEMP_BAND_HIGH = 50f
private val GRID_MIN_WIDTH = 460.dp

@Composable
private fun MetricChartCard(
    label: String,
    points: List<Float>,
    yMin: Float,
    yMax: Float,
    gaps: List<Int>,
    intervalSec: Int,
    format: (Float) -> String,
    modifier: Modifier = Modifier,
    naLabel: String = "—",
    emptyLabel: String = "collecting data…",
) {
    val current = points.lastOrNull { !it.isNaN() }
    val validCount = points.count { !it.isNaN() }
    val gapCount = gaps.count { it in 1 until points.size }
    // Newest sample being N/A means the metric is unavailable *right now* (e.g. power while charging),
    // so show naLabel instead of a stale earlier reading.
    val latestIsNa = points.lastOrNull()?.isNaN() == true

    Panel(
        label = label,
        modifier = modifier,
        trailing = {
            Text(
                text = if (latestIsNa) naLabel else current?.let(format) ?: naLabel,
                style = MaterialTheme.typography.titleMedium.mono(),
                color = LocalPalette.current.accent,
            )
        },
    ) {
        Box(Modifier.fillMaxWidth().height(128.dp)) {
            if (validCount < 2) {
                Text(
                    text = emptyLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                ChartCanvas(points, yMin, yMax, gaps, format, intervalSec, naLabel, Modifier.fillMaxSize())
                // Corner axis labels sit on a panel-colored chip so a drifting gap rule (or the
                // line/fill) passing behind them never bleeds through and makes the value unreadable.
                AxisLabel(format(yMax), Modifier.align(Alignment.TopStart))
                AxisLabel(format(yMin), Modifier.align(Alignment.BottomStart))
            }
        }
        Spacer(Modifier.size(8.dp))
        val base = "whole session · every ${intervalSec}s · ${sessionSpan(points.size, intervalSec)}"
        Text(
            text = if (gapCount > 0) "$base · dashed line = app was stopped, then resumed" else base,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChartCanvas(
    points: List<Float>,
    yMin: Float,
    yMax: Float,
    gaps: List<Int>,
    format: (Float) -> String,
    intervalSec: Int,
    naLabel: String,
    modifier: Modifier,
) {
    val pal = LocalPalette.current
    val measurer = rememberTextMeasurer()
    val bubbleStyle = MaterialTheme.typography.labelSmall.mono()
        .copy(color = MaterialTheme.colorScheme.onSurface)
    val axisStyle = MaterialTheme.typography.labelSmall

    // Horizontal drag scrubs the series; vertical drags fall through to the page's scroll. The x of
    // the touch (null when not scrubbing) is resolved to the nearest sample at draw time, so it stays
    // valid as new points stream in mid-scrub.
    var touchX by remember { mutableStateOf<Float?>(null) }

    Canvas(
        modifier.pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { touchX = it.x.coerceIn(0f, size.width.toFloat()) },
                onDragEnd = { touchX = null },
                onDragCancel = { touchX = null },
                onHorizontalDrag = { change, _ ->
                    touchX = change.position.x.coerceIn(0f, size.width.toFloat())
                    change.consume()
                },
            )
        }
    ) {
        val w = size.width
        val h = size.height
        // Recessive horizontal gridlines.
        listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { f ->
            drawLine(PanelBorder, Offset(0f, h * f), Offset(w, h * f), 1.dp.toPx())
        }
        val range = (yMax - yMin).coerceAtLeast(0.0001f)
        val n = points.size
        fun px(i: Int) = if (n > 1) i.toFloat() / (n - 1) * w else 0f
        fun py(v: Float) = h - ((v - yMin) / range).coerceIn(0f, 1f) * h

        val gapSet = gaps.filter { it in 1 until n }.toHashSet()
        val line = Path()
        var started = false
        var firstX = 0f
        var lastX = 0f
        var any = false
        points.forEachIndexed { i, v ->
            // Break the line at a resume boundary so restored and live runs never connect.
            if (i in gapSet) started = false
            if (v.isNaN()) {
                started = false
            } else {
                val x = px(i)
                val y = py(v)
                if (!started) {
                    line.moveTo(x, y); started = true
                    if (!any) { firstX = x; any = true }
                } else {
                    line.lineTo(x, y)
                }
                lastX = x
            }
        }
        if (!any) return@Canvas

        val fill = Path().apply {
            addPath(line)
            lineTo(lastX, h)
            lineTo(firstX, h)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(pal.accent.copy(alpha = 0.22f), Color.Transparent)))
        drawPath(
            line,
            brush = Brush.horizontalGradient(pal.accentRamp),
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        val lastV = points.last { !it.isNaN() }
        drawCircle(pal.accent, radius = 3.dp.toPx(), center = Offset(lastX, py(lastV)))

        // Explicit interruption markers: a dashed vertical rule + a small cap at each seam where
        // recording stopped and later resumed, so a restored run never looks continuous.
        gapSet.forEach { g ->
            val x = (px(g - 1) + px(g)) / 2f
            drawLine(
                color = StatusWarm.copy(alpha = 0.75f),
                start = Offset(x, 0f),
                end = Offset(x, h),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
            )
            val cap = 4.dp.toPx()
            drawPath(
                Path().apply {
                    moveTo(x - cap, 0f); lineTo(x + cap, 0f); lineTo(x, cap * 1.6f); close()
                },
                color = StatusWarm,
            )
        }

        // Scrub readout: crosshair at the nearest sample plus a value·age bubble.
        val tx = touchX
        if (tx != null && n > 1) {
            val target = (tx / w * (n - 1)).roundToInt().coerceIn(0, n - 1)
            // Snap to the closest real reading so scrubbing over a charging break / resume gap still
            // lands on a value rather than a hole.
            val i = (0 until n).filter { !points[it].isNaN() }.minByOrNull { kotlin.math.abs(it - target) }
            if (i != null) {
                val x = px(i)
                val v = points[i]
                drawLine(pal.accent.copy(alpha = 0.55f), Offset(x, 0f), Offset(x, h), 1.dp.toPx())
                drawCircle(pal.accent, radius = 3.5.dp.toPx(), center = Offset(x, py(v)))

                val ageSteps = (n - 1) - i
                val ageSec = ageSteps * intervalSec
                val age = when {
                    ageSteps == 0 -> "now"
                    ageSec < 60 -> "−${ageSec}s"
                    else -> "−${ageSec / 60}m"
                }
                val label = "${format(v)} · $age"
                val tl = measurer.measure(label, bubbleStyle)
                val padX = 6.dp.toPx()
                val padY = 3.dp.toPx()
                val bw = tl.size.width + padX * 2
                val bh = tl.size.height + padY * 2
                // The top-left axis chip (yMax) is a Compose overlay drawn above this canvas, so keep
                // the bubble clear of it — start no further left than just past that chip's width.
                val leftChipW = measurer.measure(format(yMax), axisStyle).size.width + 8.dp.toPx()
                val minBx = leftChipW + 4.dp.toPx()
                val bx = (x - bw / 2f).coerceIn(minBx, (w - bw - 2.dp.toPx()).coerceAtLeast(minBx))
                val by = 2.dp.toPx()
                val radius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                drawRoundRect(Panel.copy(alpha = 0.94f), Offset(bx, by), Size(bw, bh), radius)
                drawRoundRect(
                    pal.accent.copy(alpha = 0.45f), Offset(bx, by), Size(bw, bh), radius,
                    style = Stroke(width = 1.dp.toPx()),
                )
                drawText(tl, topLeft = Offset(bx + padX, by + padY))
            }
        }
    }
}

/** An axis value on a subtle panel chip, kept legible over whatever the canvas draws behind it. */
@Composable
private fun AxisLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .background(Panel.copy(alpha = 0.78f), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

private fun sessionSpan(count: Int, intervalSec: Int): String {
    val secs = count * intervalSec
    return if (secs < 60) "${secs}s" else "${secs / 60}m"
}
