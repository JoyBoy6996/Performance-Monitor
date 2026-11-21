package com.framescope.monitor.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.framescope.monitor.core.finiteOrNull
import com.framescope.monitor.ui.theme.MonoInline
import com.framescope.monitor.ui.theme.Space
import java.util.Locale

/** One line in a graph detail card. Fixed bounds are used only when they describe a real scale. */
data class TelemetrySeries(
    val label: String,
    val values: List<Float>,
    val color: Color,
    val unit: String = "",
    val min: Float? = null,
    val max: Float? = null
)

/** Compact time labels shared by every telemetry graph. */
@Composable
fun TimeAxis(durationMs: Long, sampleCount: Int) {
    if (sampleCount < 2 || durationMs <= 0L) return
    val ticks = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Space.xs),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        ticks.forEach { fraction ->
            Text(
                text = shortDuration((durationMs * fraction).toLong()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Draws every series on its own real range. The legend prints those ranges, so a
 * line is never mistaken for a shared scale when two metrics use different units.
 */
@Composable
fun TelemetryChart(
    series: List<TelemetrySeries>,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 150.dp
) {
    val usable = series.filter { it.values.size >= 2 }
    if (usable.isEmpty()) {
        Text(
            text = "No samples recorded",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
        val grid = gridColor
        for (i in 0..4) {
            val y = size.height * i / 4f
            drawLine(
                color = grid,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.dp.toPx()
            )
        }
        usable.forEach { line ->
            val values = line.values
            val low = line.min ?: values.minOrNull() ?: 0f
            val high = line.max ?: values.maxOrNull() ?: 1f
            val span = (high - low).takeIf { it > 0f } ?: 1f
            val step = size.width / (values.size - 1)
            val points = values.mapIndexed { index, value ->
                Offset(
                    x = index * step,
                    y = size.height - ((value - low) / span).coerceIn(0f, 1f) * size.height
                )
            }
            val path = Path().apply {
                moveTo(points.first().x, points.first().y)
                for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
            }
            drawPath(
                path = path,
                color = line.color,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )
        }
    }

    Spacer(Modifier.height(Space.sm))
    usable.forEach { line ->
        val valid = line.values.mapNotNull { it.finiteOrNull() }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(line.color)
            )
            Text(
                text = line.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = Space.sm).weight(1f)
            )
            Text(
                text = if (valid.isEmpty()) {
                    "--"
                } else {
                    "${num(valid.minOrNull(), 1)}–${num(valid.maxOrNull(), 1)}" +
                        if (line.unit.isBlank()) "" else " ${line.unit}"
                },
                style = MonoInline,
                color = line.color
            )
        }
    }
}

/** Frame pacing spikes, drawn as bars so a single long frame stays visible. */
@Composable
fun SpikeChart(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 130.dp
) {
    val clean = values.mapNotNull { it.finiteOrNull()?.takeIf { v -> v >= 0f } }
    if (clean.size < 2) {
        Text(
            text = "No samples recorded",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    val ceiling = (clean.maxOrNull() ?: 1f).coerceAtLeast(1f)
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
        val grid = gridColor
        for (i in 0..4) {
            val y = size.height * i / 4f
            drawLine(
                color = grid,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.dp.toPx()
            )
        }
        val slot = size.width / clean.size
        val barWidth = (slot * 0.7f).coerceAtLeast(1f)
        clean.forEachIndexed { index, value ->
            val barHeight = (value / ceiling).coerceIn(0f, 1f) * size.height
            drawRect(
                color = color,
                topLeft = Offset(index * slot + (slot - barWidth) / 2f, size.height - barHeight),
                size = Size(barWidth, barHeight)
            )
        }
    }
    Spacer(Modifier.height(Space.xs))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("MAX", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = "${num(clean.maxOrNull(), 1)} ms",
            style = MonoInline,
            color = color
        )
    }
}

private fun num(value: Float?, decimals: Int = 1): String {
    val number = value.finiteOrNull() ?: return "--"
    return String.format(Locale.US, "%.${decimals}f", number)
}

private fun shortDuration(ms: Long): String {
    val seconds = (ms / 1000L).coerceAtLeast(0L)
    return if (seconds < 60L) {
        "${seconds}s"
    } else {
        "${seconds / 60L}m${seconds % 60L}s"
    }
}
