package com.corewatch.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.corewatch.ui.theme.LocalPalette
import com.corewatch.ui.theme.PanelBorder
import com.corewatch.ui.theme.StatusWarm
import com.corewatch.ui.theme.mono

private fun mhzToGhz(mhz: Float): String = String.format("%.2f GHz", mhz / 1000f)
private fun bytesToGb(bytes: Float): String = String.format("%.1f GB", bytes / 1_073_741_824f)
private fun degC(t: Float): String = String.format("%.1f °C", t)
private fun watts(w: Float): String = String.format("%.2f W", w) // signed: −draining, +charging

/** Both session charts, stacked. Renders whatever data exists so far this session. */
@Composable
fun HistoryCharts(
    cpuPoints: List<Float>,
    cpuMaxMhz: Int?,
    ramPoints: List<Float>,
    ramTotalBytes: Long,
    tempPoints: List<Float>,
    powerPoints: List<Float>,
    gaps: List<Int>,
    intervalSec: Int,
    modifier: Modifier = Modifier,
) {
    val cpuTop = cpuMaxMhz?.toFloat()
        ?: cpuPoints.filter { !it.isNaN() }.maxOrNull()?.times(1.1f)
        ?: 1f
    val ramTop = if (ramTotalBytes > 0) ramTotalBytes.toFloat()
    else ramPoints.maxOrNull()?.times(1.1f) ?: 1f

    // Temperature sits in a fixed 20–50°C band (so slow drift reads honestly, not flat or jumpy),
    // expanding only if a reading falls outside it.
    val tempValid = tempPoints.filter { !it.isNaN() }
    val tempLo = minOf(TEMP_BAND_LOW, tempValid.minOrNull() ?: TEMP_BAND_LOW)
    val tempHi = maxOf(TEMP_BAND_HIGH, tempValid.maxOrNull() ?: TEMP_BAND_HIGH)

    // Power is signed watts; always keep 0 in view so draining (−) vs charging (+) reads honestly,
    // then pad so a nearly-flat trace isn't dead against the axis.
    val powerValid = powerPoints.filter { !it.isNaN() }
    var powerLo = minOf(0f, powerValid.minOrNull() ?: 0f)
    var powerHi = maxOf(0f, powerValid.maxOrNull() ?: 0f)
    if (powerHi - powerLo < 1f) powerHi = powerLo + 1f

    // Wired once, placed into a 2×2 grid on wide screens (landscape) or a single column otherwise.
    val cards = listOf<@Composable (Modifier) -> Unit>(
        { m -> MetricChartCard("CPU history", cpuPoints, 0f, cpuTop, gaps, intervalSec, ::mhzToGhz, m) },
        { m -> MetricChartCard("Memory history", ramPoints, 0f, ramTop, gaps, intervalSec, ::bytesToGb, m) },
        { m -> MetricChartCard("Battery temperature", tempPoints, tempLo, tempHi, gaps, intervalSec, ::degC, m) },
        { m -> MetricChartCard("Power draw", powerPoints, powerLo, powerHi, gaps, intervalSec, ::watts, m) },
    )

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
) {
    val current = points.lastOrNull { !it.isNaN() }
    val validCount = points.count { !it.isNaN() }
    val gapCount = gaps.count { it in 1 until points.size }

    Panel(
        label = label,
        modifier = modifier,
        trailing = {
            Text(
                text = current?.let(format) ?: "—",
                style = MaterialTheme.typography.titleMedium.mono(),
                color = LocalPalette.current.accent,
            )
        },
    ) {
        Box(Modifier.fillMaxWidth().height(128.dp)) {
            if (validCount < 2) {
                Text(
                    text = "collecting data…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                ChartCanvas(points, yMin, yMax, gaps, Modifier.fillMaxSize())
                Text(
                    text = format(yMax),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.TopStart),
                )
                Text(
                    text = format(yMin),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.BottomStart),
                )
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
private fun ChartCanvas(points: List<Float>, yMin: Float, yMax: Float, gaps: List<Int>, modifier: Modifier) {
    val pal = LocalPalette.current
    Canvas(modifier) {
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
    }
}

private fun sessionSpan(count: Int, intervalSec: Int): String {
    val secs = count * intervalSec
    return if (secs < 60) "${secs}s" else "${secs / 60}m"
}
