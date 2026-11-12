package com.framescope.monitor.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import com.framescope.monitor.ui.theme.LocalGlassColors
import com.framescope.monitor.ui.theme.LocalMotionEnabled
import com.framescope.monitor.ui.theme.glass
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.luminance
import com.framescope.monitor.ui.theme.MonoBlock
import com.framescope.monitor.ui.theme.MonoCaption
import com.framescope.monitor.ui.theme.MonoHero
import com.framescope.monitor.ui.theme.MonoInline
import com.framescope.monitor.ui.theme.MonoValue
import com.framescope.monitor.ui.theme.SectionLabel
import com.framescope.monitor.ui.theme.Space
import com.framescope.monitor.ui.theme.status
import kotlin.math.cos
import kotlin.math.sin

/**
 * The base container of the app.
 *
 * Panels sit on [androidx.compose.material3.ColorScheme.surfaceContainer] and are separated from
 * the background by a hairline instead of a shadow: elevation in a data dense screen reads as
 * noise, a hairline reads as structure.
 */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant,
    glass: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    if (glass) {
        val glassColors = LocalGlassColors.current
        Box(
            modifier = modifier.glass(
                shape = shape,
                tint = containerColor.copy(alpha = glassColors.fill.alpha),
                hairline = glassColors.hairline,
                elevation = 10.dp
            )
        ) {
            CompositionLocalProvider(LocalContentColor provides contentColor) {
                Column(Modifier.padding(Space.lg), content = content)
            }
        }
    } else {
        Surface(
            modifier = modifier,
            shape = shape,
            color = containerColor,
            contentColor = contentColor,
            border = BorderStroke(1.dp, borderColor),
            tonalElevation = 0.dp
        ) {
            Column(Modifier.padding(Space.lg), content = content)
        }
    }
}

/** Hairline used between rows inside a [Panel]. */
@Composable
fun PanelDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        thickness = 1.dp,
        color = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
            Color.White.copy(alpha = 0.08f)
        } else {
            Color.Black.copy(alpha = 0.12f)
        }
    )
}

/** Uppercase group label. */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text.uppercase(),
            style = SectionLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke(this)
    }
}

/**
 * One measured value. The number is the loud part, the label is quiet: the eye should land on
 * the reading first and the unit second.
 */
@Composable
fun StatCell(
    label: String,
    value: String,
    unit: String? = null,
    accent: Color = MaterialTheme.colorScheme.onSurface,
    meter: Float? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(text = value, style = MonoValue, color = accent, maxLines = 1)
            if (!unit.isNullOrEmpty()) {
                Text(
                    text = unit,
                    style = MonoCaption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 3.dp, bottom = 2.dp)
                )
            }
        }
        if (meter != null) {
            Spacer(Modifier.height(Space.xs))
            BarMeter(
                value = meter,
                modifier = Modifier.fillMaxWidth(),
                color = accent
            )
        }
    }
}

/** Label on the left, measured value on the right. */
@Composable
fun MetricLine(
    label: String,
    value: String,
    accent: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f, fill = false),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(Space.sm))
        Text(text = value, style = MonoInline, color = accent, maxLines = 1)
    }
}

/** Small state chip: a dot and a word. */
@Composable
fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .glass(
                shape = RoundedCornerShape(50),
                tint = color.copy(alpha = 0.14f),
                hairline = color.copy(alpha = 0.30f),
                blurRadius = 20.dp,
                elevation = 2.dp
            )
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(start = 6.dp),
            maxLines = 1
        )
    }
}

/**
 * Thin meter. The track is a real colour instead of a white overlay, because a white overlay
 * disappears completely on a light background.
 */
@Composable
fun BarMeter(
    value: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    height: Dp = 4.dp
) {
    val track = MaterialTheme.status.track
    val ratio = value.coerceIn(0f, 1f)
    Canvas(modifier = modifier.height(height)) {
        val radius = size.height / 2f
        drawRoundRect(
            color = track,
            cornerRadius = CornerRadius(radius)
        )
        if (ratio > 0f) {
            drawRoundRect(
                color = color,
                size = Size(size.width * ratio, size.height),
                cornerRadius = CornerRadius(radius)
            )
        }
    }
}

/** Colour for a frame rate reading, relative to the smoothness target. */
@Composable
fun fpsStateColor(fps: Float, target: Int): Color {
    val palette = MaterialTheme.status
    return when {
        fps <= 0f -> palette.idle
        fps >= target -> palette.good
        fps >= target * 0.75f -> palette.warn
        else -> palette.critical
    }
}

/**
 * Frame rate history.
 *
 * The line is a smoothed spline rather than a polyline: raw frame rate is noisy and a jagged
 * polyline reads as noise in the data instead of a real drop.
 */
@Composable
fun Sparkline(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    target: Float? = null,
    showTarget: Boolean = true,
    trackColor: Color = MaterialTheme.status.track,
    gridColor: Color = MaterialTheme.status.grid
) {
    val headAlpha = rememberPulseAlpha()

    Canvas(modifier = modifier) {
        if (values.size < 2) return@Canvas
        val ceiling = maxOf(
            target ?: 0f,
            (values.maxOrNull() ?: 1f) * 1.15f
        ).coerceAtLeast(1f)
        val step = size.width / (values.size - 1)
        val points = values.mapIndexed { index, value ->
            val x = index * step
            val y = size.height - (value / ceiling).coerceIn(0f, 1f) * size.height
            Offset(x, y)
        }

        val (line, fill) = smoothPaths(points, size.height)

        // Threshold marker sits behind the line so the curve stays readable on top of it.
        if (target != null && showTarget) {
            val ty = size.height - (target / ceiling).coerceIn(0f, 1f) * size.height
            drawLine(
                color = gridColor,
                start = Offset(0f, ty),
                end = Offset(size.width, ty),
                strokeWidth = 1.dp.toPx()
            )
        }

        drawPath(
            path = fill,
            brush = Brush.verticalGradient(
                colors = listOf(color.copy(alpha = 0.26f), color.copy(alpha = 0f))
            )
        )
        drawPath(path = line, color = color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))

        val head = points.last()
        drawCircle(color = color.copy(alpha = 0.25f), radius = 5.dp.toPx(), center = head)
        drawCircle(color = color.copy(alpha = headAlpha), radius = 2.5.dp.toPx(), center = head)

        // Baseline keeps the chart anchored when the values sit near the top.
        drawLine(
            color = trackColor,
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = 1.dp.toPx()
        )
    }
}

/**
 * Builds a smooth curve through [points] and the same curve closed down to the baseline.
 * Control points are clamped so the spline can never leave the plot area.
 */
private fun smoothPaths(points: List<Offset>, height: Float): Pair<Path, Path> {
    val line = Path()
    val fill = Path()
    if (points.isEmpty()) return line to fill

    line.moveTo(points.first().x, points.first().y)
    fill.moveTo(points.first().x, height)
    fill.lineTo(points.first().x, points.first().y)

    for (i in 0 until points.size - 1) {
        val p0 = points[(i - 1).coerceAtLeast(0)]
        val p1 = points[i]
        val p2 = points[i + 1]
        val p3 = points[(i + 2).coerceAtMost(points.size - 1)]
        val c1 = Offset(
            p1.x + (p2.x - p0.x) / 6f,
            (p1.y + (p2.y - p0.y) / 6f).coerceIn(0f, height)
        )
        val c2 = Offset(
            p2.x - (p3.x - p1.x) / 6f,
            (p2.y - (p3.y - p1.y) / 6f).coerceIn(0f, height)
        )
        line.cubicTo(c1.x, c1.y, c2.x, c2.y, p2.x, p2.y)
        fill.cubicTo(c1.x, c1.y, c2.x, c2.y, p2.x, p2.y)
    }
    fill.lineTo(points.last().x, height)
    fill.close()
    return line to fill
}

/**
 * Circular frame rate gauge.
 *
 * The arc is animated towards the new reading, the notch marks the smoothness target, and the
 * ticks give the eye a scale without printing numbers around the circle.
 */
@Composable
fun FpsGauge(
    value: Float,
    target: Int,
    maxValue: Float,
    modifier: Modifier = Modifier,
    diameter: Dp = 152.dp,
    showValue: Boolean = true
) {
    val arcColor = fpsStateColor(value, target)
    val trackColor = MaterialTheme.status.track.copy(alpha = 0.68f)
    val gridColor = MaterialTheme.status.grid
    val gridStrongColor = MaterialTheme.status.gridStrong
    val notchColor = MaterialTheme.colorScheme.onSurfaceVariant
    val animated by animateFloatAsState(
        targetValue = (value / maxValue.coerceAtLeast(1f)).coerceIn(0f, 1f),
        animationSpec = tween(520, easing = FastOutSlowInEasing),
        label = "gauge"
    )
    val targetRatio = (target / maxValue.coerceAtLeast(1f)).coerceIn(0f, 1f)

    Box(modifier = modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(diameter)) {
            val stroke = 9.dp.toPx()
            val arcSize = Size(size.minDimension - stroke, size.minDimension - stroke)
            val topLeft = Offset(
                (size.width - arcSize.width) / 2f,
                (size.height - arcSize.height) / 2f
            )
            val startAngle = 135f
            val sweep = 270f

            drawArc(
                color = trackColor,
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )

            // Scale ticks.
            val radius = size.minDimension / 2f - stroke / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            for (i in 0..6) {
                val angle = Math.toRadians((startAngle + sweep * i / 6f).toDouble())
                val inner = radius - 4.dp.toPx()
                val outer = radius
                val isTarget = i == 6
                drawLine(
                    color = if (isTarget) gridStrongColor else gridColor,
                    start = Offset(
                        center.x + (inner * cos(angle)).toFloat(),
                        center.y + (inner * sin(angle)).toFloat()
                    ),
                    end = Offset(
                        center.x + (outer * cos(angle)).toFloat(),
                        center.y + (outer * sin(angle)).toFloat()
                    ),
                    strokeWidth = if (isTarget) 2.dp.toPx() else 1.dp.toPx()
                )
            }

            if (animated > 0f) {
                drawArc(
                    color = arcColor,
                    startAngle = startAngle,
                    sweepAngle = sweep * animated,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }

            // Target notch.
            val notchAngle = Math.toRadians((startAngle + sweep * targetRatio).toDouble())
            val outer = radius + 1.dp.toPx()
            val inner = radius - 7.dp.toPx()
            drawLine(
                color = notchColor,
                start = Offset(
                    center.x + (inner * cos(notchAngle)).toFloat(),
                    center.y + (inner * sin(notchAngle)).toFloat()
                ),
                end = Offset(
                    center.x + (outer * cos(notchAngle)).toFloat(),
                    center.y + (outer * sin(notchAngle)).toFloat()
                ),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        if (showValue) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (value > 0f) String.format("%.0f", value) else "--",
                    style = MonoHero,
                    color = if (value > 0f) arcColor else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "FPS",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * A row inside a settings list: title, optional supporting line and a trailing control.
 */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null && enabled) {
                    Modifier.clickable { onClick() }
                } else {
                    Modifier
                }
            )
            .padding(horizontal = Space.lg, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            Box(Modifier.padding(end = Space.md)) { leading() }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            if (!supporting.isNullOrBlank()) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        if (trailing != null) {
            Box(Modifier.padding(start = Space.md)) { trailing() }
        }
    }
}

/**
 * Segmented control.
 *
 * Built by hand instead of using FilterChip so the selected segment reads as one continuous
 * control rather than a row of separate pills.
 */
@Composable
fun SegmentedOptions(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LocalGlassColors.current.fillNested)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        options.forEach { option ->
            val isSelected = option.equals(selected, ignoreCase = true)
            val background by animateColorAsState(
                targetValue = if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    Color.Transparent
                },
                animationSpec = tween(180),
                label = "segment"
            )
            val content by animateColorAsState(
                targetValue = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                animationSpec = tween(180),
                label = "segmentContent"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (isSelected) {
                            Modifier.glass(
                                shape = RoundedCornerShape(8.dp),
                                tint = background.copy(alpha = 0.90f),
                                hairline = LocalGlassColors.current.hairlineHighlight,
                                blurRadius = 20.dp,
                                elevation = 2.dp
                            )
                        } else {
                            Modifier
                        }
                    )
                    .clickable { onSelect(option) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = option,
                    style = MaterialTheme.typography.labelMedium,
                    color = content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Multi select chips for short, fixed lists. */
@Composable
fun ChipRow(
    options: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        verticalArrangement = Arrangement.spacedBy(Space.sm)
    ) {
        options.forEach { option ->
            val isSelected = option in selected
            val background by animateColorAsState(
                targetValue = if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLowest
                },
                animationSpec = tween(160),
                label = "chip"
            )
            val content by animateColorAsState(
                targetValue = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                animationSpec = tween(160),
                label = "chipContent"
            )
            Row(
                modifier = Modifier
                    .glass(
                        shape = RoundedCornerShape(8.dp),
                        tint = if (isSelected) {
                            background.copy(alpha = 0.88f)
                        } else {
                            LocalGlassColors.current.fillNested
                        },
                        hairline = if (isSelected) {
                            LocalGlassColors.current.hairlineHighlight
                        } else {
                            LocalGlassColors.current.hairline
                        },
                        blurRadius = 20.dp,
                        elevation = 2.dp
                    )
                    .clickable { onToggle(option) }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = content,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = option,
                    style = MaterialTheme.typography.labelMedium,
                    color = content,
                    maxLines = 1
                )
            }
        }
    }
}

/** Monospace block used for shell commands and their output. */
@Composable
fun TerminalBlock(
    text: String,
    modifier: Modifier = Modifier,
    onCopy: (() -> Unit)? = null,
    maxLines: Int = Int.MAX_VALUE
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)) {
            Text(
                text = text,
                style = MonoBlock,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (onCopy != null) {
                IconButton(onClick = { onCopy() }, modifier = Modifier.size(30.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = "Copy",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }
    }
}

/**
 * Slider with the current value printed on the same line as the label, so the number never
 * hides underneath the thumb.
 */
@Composable
fun ValueSlider(
    label: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = valueText,
                style = MonoInline,
                color = MaterialTheme.colorScheme.primary
            )
        }
        androidx.compose.material3.Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps
        )
    }
}

/** Empty state: an icon, a headline and one sentence of guidance. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .glass(
                tint = LocalGlassColors.current.fill,
                hairline = LocalGlassColors.current.hairline
            )
            .padding(horizontal = Space.xl, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = Space.lg)
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Space.xs)
        )
    }
}

/**
 * Slow breathing alpha used for live indicators.
 *
 * It stops completely when the system asks for animations to be removed, which is also what
 * lets the interface be rendered without a running frame clock.
 */
@Composable
fun rememberPulseAlpha(durationMs: Int = 1100): Float {
    if (!LocalMotionEnabled.current) return 1f
    val transition = rememberInfiniteTransition(label = "pulse")
    val value by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMs, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseValue"
    )
    return value
}


/**
 * A single line text field.
 *
 * The app uses its own field instead of the platform one so that every input
 * looks the same: monospaced, flat, with the label sitting inside the field.
 */
@Composable
fun MonoField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true
) {
    val shape = RoundedCornerShape(10.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = MonoBlock.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(horizontal = Space.md, vertical = 11.dp),
        decorationBox = { inner ->
            if (value.isEmpty()) {
                Text(
                    text = label,
                    style = MonoBlock.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                )
            }
            inner()
        }
    )
}
