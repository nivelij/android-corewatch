package com.corewatch.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.corewatch.ui.theme.LocalPalette
import com.corewatch.ui.theme.PanelBorder
import com.corewatch.ui.theme.mono

private fun mhzToGhz(mhz: Float): String = String.format("%.2f GHz", mhz / 1000f)
private fun bytesToGb(bytes: Float): String = String.format("%.1f GB", bytes / 1_073_741_824f)

/** Both session charts, stacked. Renders whatever data exists so far this session. */
@Composable
fun HistoryCharts(
    cpuPoints: List<Float>,
    cpuMaxMhz: Int?,
    ramPoints: List<Float>,
    ramTotalBytes: Long,
    intervalSec: Int,
    modifier: Modifier = Modifier,
) {
    val cpuTop = cpuMaxMhz?.toFloat()
        ?: cpuPoints.filter { !it.isNaN() }.maxOrNull()?.times(1.1f)
        ?: 1f
    val ramTop = if (ramTotalBytes > 0) ramTotalBytes.toFloat()
    else ramPoints.maxOrNull()?.times(1.1f) ?: 1f

    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        MetricChartCard(
            label = "CPU history",
            points = cpuPoints,
            yMin = 0f,
            yMax = cpuTop,
            intervalSec = intervalSec,
            format = ::mhzToGhz,
        )
        MetricChartCard(
            label = "Memory history",
            points = ramPoints,
            yMin = 0f,
            yMax = ramTop,
            intervalSec = intervalSec,
            format = ::bytesToGb,
        )
    }
}

@Composable
private fun MetricChartCard(
    label: String,
    points: List<Float>,
    yMin: Float,
    yMax: Float,
    intervalSec: Int,
    format: (Float) -> String,
) {
    val current = points.lastOrNull { !it.isNaN() }
    val validCount = points.count { !it.isNaN() }

    Panel(
        label = label,
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
                ChartCanvas(points, yMin, yMax, Modifier.fillMaxSize())
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
        Text(
            text = "whole session · every ${intervalSec}s · ${sessionSpan(points.size, intervalSec)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChartCanvas(points: List<Float>, yMin: Float, yMax: Float, modifier: Modifier) {
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

        val line = Path()
        var started = false
        var firstX = 0f
        var lastX = 0f
        var any = false
        points.forEachIndexed { i, v ->
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
    }
}

private fun sessionSpan(count: Int, intervalSec: Int): String {
    val secs = count * intervalSec
    return if (secs < 60) "${secs}s" else "${secs / 60}m"
}
