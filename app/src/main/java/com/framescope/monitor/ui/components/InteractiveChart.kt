package com.framescope.monitor.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.framescope.monitor.ui.theme.Space
import java.util.Locale

/**
 * A chart that can be touched.
 *
 * Dragging along the graph chooses a moment. A line follows the finger and the
 * readout shows the values that were really recorded at that moment: the sample
 * nearest to it, with the time of that sample printed, so the number under the
 * finger is never a value that was made up.
 *
 * The samples are drawn as they are. A drop stays a drop, because smoothing would
 * turn a real stall into a soft slope.
 */
@Composable
fun InteractiveLineChart(
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
    selectedTimeMs: Long? = null,
    onSelectTime: ((Long?) -> Unit)? = null,
    events: List<ChartEvent> = emptyList(),
    showTimeAxis: Boolean = true,
    yAxisWidth: Dp = 40.dp,
    maxDrawnPoints: Int = 600
) {
    val usable = remember(series) { series.filter { it.points.size >= 2 } }
    if (usable.isEmpty()) {
        Text(
            text = "No samples recorded",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    val startMs = remember(usable) { usable.minOf { it.points.first().timeMs } }
    val endMs = remember(usable) { usable.maxOf { it.points.last().timeMs } }
    val span = (endMs - startMs).coerceAtLeast(1L)
    val target = remember(usable) { usable.firstNotNullOfOrNull { it.target } }
    val scale = remember(usable, target) {
        buildChartScale(
            values = usable.flatMap { line -> line.points.map { it.value } },
            pinnedMin = usable.firstOrNull { it.min != null }?.min,
            pinnedMax = usable.firstOrNull { it.max != null }?.max,
            target = target
        )
    }
    val drawn = remember(usable, maxDrawnPoints) {
        usable.map { line -> line to downsamplePreservingSpikes(line.points, maxDrawnPoints) }
    }

    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val axisText = MaterialTheme.colorScheme.onSurfaceVariant
    val crosshairColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    val targetColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val gutterPx = with(LocalDensity.current) { yAxisWidth.toPx() }
    var totalWidthPx by remember { mutableStateOf(0) }
    val plotWidthPx = (totalWidthPx - gutterPx).coerceAtLeast(1f)

    // Touching. A drag along the graph chooses a moment, a drag up or down is
    // left to the page so the list around the chart still scrolls.
    val select = onSelectTime
    val timeAt: (Float) -> Long = { x ->
        if (endMs <= startMs) {
            startMs
        } else {
            val fraction = ((x - gutterPx) / plotWidthPx).coerceIn(0f, 1f)
            startMs + (span * fraction).toLong()
        }
    }
    val touch = if (select != null) {
        Modifier
            .pointerInput(startMs, endMs, gutterPx, plotWidthPx) {
                detectTapGestures { offset -> select(timeAt(offset.x)) }
            }
            .pointerInput(startMs, endMs, gutterPx, plotWidthPx) {
                detectHorizontalDragGestures(
                    onDragStart = { offset -> select(timeAt(offset.x)) },
                    onHorizontalDrag = { change, _ ->
                        select(timeAt(change.position.x))
                        change.consume()
                    }
                )
            }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .onSizeChanged { totalWidthPx = it.width }
            .then(touch)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(start = yAxisWidth)
        ) {
            val width = size.width
            val plotHeight = size.height

            for (i in 0..4) {
                val y = plotHeight * i / 4f
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(width, y),
                    strokeWidth = 1.dp.toPx()
                )
            }

            if (target != null && target.isFinite()) {
                val y = plotHeight - scale.fraction(target) * plotHeight
                drawLine(
                    color = targetColor,
                    start = Offset(0f, y),
                    end = Offset(width, y),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(6.dp.toPx(), 5.dp.toPx())
                    )
                )
            }

            events.forEach { event ->
                if (event.timeMs < startMs || event.timeMs > endMs) return@forEach
                val x = (event.timeMs - startMs).toFloat() / span * width
                drawLine(
                    color = event.color.copy(alpha = 0.55f),
                    start = Offset(x, 0f),
                    end = Offset(x, plotHeight),
                    strokeWidth = 1.dp.toPx()
                )
            }

            drawn.forEach { (line, points) ->
                if (points.size < 2) return@forEach
                val path = Path()
                points.forEachIndexed { index, point ->
                    val x = (point.timeMs - startMs).toFloat() / span * width
                    val y = plotHeight - scale.fraction(point.value) * plotHeight
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path = path,
                    color = line.color,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            val selected = selectedTimeMs
            if (selected != null && selected in startMs..endMs) {
                val x = (selected - startMs).toFloat() / span * width
                drawLine(
                    color = crosshairColor,
                    start = Offset(x, 0f),
                    end = Offset(x, plotHeight),
                    strokeWidth = 1.dp.toPx()
                )
                usable.forEach { line ->
                    val point = nearestPoint(line.points, selected) ?: return@forEach
                    val px = (point.timeMs - startMs).toFloat() / span * width
                    val py = plotHeight - scale.fraction(point.value) * plotHeight
                    drawCircle(
                        color = line.color.copy(alpha = 0.25f),
                        radius = 6.dp.toPx(),
                        center = Offset(px, py)
                    )
                    drawCircle(color = line.color, radius = 3.dp.toPx(), center = Offset(px, py))
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .width(yAxisWidth)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End
        ) {
            listOf(scale.max, (scale.max + scale.min) / 2f, scale.min).forEach { value ->
                Text(
                    text = formatAxisValue(value),
                    style = MaterialTheme.typography.labelSmall,
                    color = axisText,
                    maxLines = 1,
                    modifier = Modifier.padding(end = Space.xs)
                )
            }
        }

        if (showTimeAxis) {
            val ticks = remember(startMs, endMs) { buildTimeTicks(startMs, endMs) }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(start = yAxisWidth),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ticks.forEach { tick ->
                    Text(
                        text = formatElapsed(tick),
                        style = MaterialTheme.typography.labelSmall,
                        color = axisText,
                        maxLines = 1
                    )
                }
            }
        }

        val selected = selectedTimeMs
        if (selected != null) {
            val onTheRight = (selected - startMs).toFloat() / span > 0.5f
            ChartTooltip(
                series = usable,
                timeMs = selected,
                modifier = Modifier
                    .align(if (onTheRight) Alignment.TopStart else Alignment.TopEnd)
                    .padding(horizontal = Space.sm, vertical = Space.xs)
            )
        }
    }
}

/** A number of a scale, printed without a trailing ".0". */
private fun formatAxisValue(value: Float): String {
    if (!value.isFinite()) return "--"
    return if (value == value.toInt().toFloat()) {
        value.toInt().toString()
    } else {
        String.format(Locale.US, "%.1f", value)
    }
}
