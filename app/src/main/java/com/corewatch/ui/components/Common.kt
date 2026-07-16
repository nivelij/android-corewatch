package com.corewatch.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.corewatch.ui.theme.Accent
import com.corewatch.ui.theme.AccentRamp
import com.corewatch.ui.theme.Panel
import com.corewatch.ui.theme.PanelBorder
import com.corewatch.ui.theme.TextLabel
import com.corewatch.ui.theme.mono

/** Dark, hairline-bordered panel with an uppercase section label + optional trailing slot. */
@Composable
fun Panel(
    label: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        color = Panel,
        border = BorderStroke(1.dp, PanelBorder),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(label, Modifier.weight(1f))
                trailing?.invoke()
            }
            Spacer(Modifier.size(12.dp))
            content()
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = TextLabel,
        fontWeight = FontWeight.Medium,
        letterSpacing = 2.sp,
        modifier = modifier,
    )
}

/** Large monospaced readout with a trailing unit. Optionally painted with the accent gradient. */
@Composable
fun MetricValue(
    value: String,
    unit: String,
    color: Color = MaterialTheme.colorScheme.onSurface,
    gradient: Boolean = false,
) {
    val base = MaterialTheme.typography.displaySmall.mono().copy(fontWeight = FontWeight.SemiBold)
    val style = if (gradient) base.copy(brush = Brush.linearGradient(AccentRamp)) else base.copy(color = color)
    Row(verticalAlignment = Alignment.Bottom) {
        Text(text = value, style = style, maxLines = 1)
        Spacer(Modifier.width(5.dp))
        Text(
            text = unit,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
    }
}

/** A glowing dot (halo + core). [pulse] animates the halo for the "LIVE" state. */
@Composable
fun GlowDot(color: Color, pulse: Boolean = false) {
    val haloAlpha = if (pulse) {
        val t = rememberInfiniteTransition(label = "pulse")
        t.animateFloatValue(0.15f, 0.5f, 950)
    } else 0.3f
    Box(contentAlignment = Alignment.Center) {
        Box(Modifier.size(16.dp).background(color.copy(alpha = haloAlpha), CircleShape)) {}
        Box(Modifier.size(7.dp).background(color, CircleShape)) {}
    }
}

@Composable
private fun androidx.compose.animation.core.InfiniteTransition.animateFloatValue(
    from: Float,
    to: Float,
    durationMs: Int,
): Float = animateFloat(
    initialValue = from,
    targetValue = to,
    animationSpec = infiniteRepeatable(tween(durationMs), RepeatMode.Reverse),
    label = "f",
).value

@Composable
fun LiveBadge(live: Boolean) {
    val color = if (live) Accent else MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        GlowDot(color = color, pulse = live)
        Spacer(Modifier.width(7.dp))
        Text(
            text = if (live) "LIVE" else "RANGE",
            style = MaterialTheme.typography.labelSmall,
            color = color,
            letterSpacing = 1.5.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** A label-over-value cell used inside the system-info grid. Values are accent gold. */
@Composable
fun InfoCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(3.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = Accent,
        )
    }
}

/** Sparkline with a gradient stroke and a soft gradient fill below the line. */
@Composable
fun Sparkline(values: List<Float>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val minV = values.min()
        val maxV = values.max()
        val range = (maxV - minV).coerceAtLeast(0.0001f)
        val stepX = size.width / (values.size - 1)
        fun px(i: Int) = i * stepX
        fun py(v: Float) = size.height - ((v - minV) / range) * size.height * 0.9f - size.height * 0.05f

        val line = Path()
        values.forEachIndexed { i, v ->
            if (i == 0) line.moveTo(px(i), py(v)) else line.lineTo(px(i), py(v))
        }
        val fill = Path().apply {
            addPath(line)
            lineTo(px(values.lastIndex), size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(
            path = fill,
            brush = Brush.verticalGradient(
                listOf(Accent.copy(alpha = 0.28f), Color.Transparent),
            ),
        )
        drawPath(
            path = line,
            brush = Brush.horizontalGradient(AccentRamp),
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
