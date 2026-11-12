package com.framescope.monitor.ui.screens

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.framescope.monitor.AppContainer
import com.framescope.monitor.core.OverlayStyle
import com.framescope.monitor.ui.FrameScopeScreen
import com.framescope.monitor.ui.components.ChipRow
import com.framescope.monitor.ui.components.Panel
import com.framescope.monitor.ui.components.SectionHeader
import com.framescope.monitor.ui.components.SegmentedOptions
import com.framescope.monitor.ui.components.Sparkline
import com.framescope.monitor.ui.components.ValueSlider
import com.framescope.monitor.ui.theme.MonoBlock
import com.framescope.monitor.ui.theme.Space
import com.framescope.monitor.ui.theme.status
import java.util.Locale

private val ALL_ROWS = listOf(
    "FPS", "FRAME", "CPU", "CPUF", "GPU", "TEMP", "BAT", "POWER", "MEM", "REFRESH", "APP"
)

private val STYLE_LABELS = listOf("Pill", "Compact", "Classic", "Detailed", "RTSS")

@Composable
fun OverlayScreen(
    container: AppContainer,
    onRequestOverlayPermission: () -> Unit,
    bottomPadding: Dp = 24.dp
) {
    val context = LocalContext.current
    val settings = container.settings
    val running by container.overlay.running.collectAsState()
    val fps by container.engine.fps.collectAsState()
    val system by container.engine.system.collectAsState()
    val style by settings.overlayStyle.flow.collectAsState()
    val rows by settings.overlayRows.flow.collectAsState()
    val opacity by settings.overlayOpacity.flow.collectAsState()
    val scale by settings.overlayScale.flow.collectAsState()
    val showGraph by settings.overlayShowGraph.flow.collectAsState()
    val graphHeight by settings.overlayGraphHeight.flow.collectAsState()
    val threshold = settings.fpsThreshold.flow.collectAsState().value

    var canDraw by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    LifecycleResumeEffect(Unit) {
        canDraw = Settings.canDrawOverlays(context)
        onPauseOrDispose { }
    }

    FrameScopeScreen(
        title = "Overlay",
        subtitle = "Live telemetry on top of any app",
        bottomPadding = bottomPadding
    ) {
        item {
            Panel {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = if (running) "Overlay is running" else "Overlay is off",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (running) {
                                "Drag to reposition, tap to collapse."
                            } else {
                                "Start it, then leave the app you want to measure."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Spacer(Modifier.width(Space.md))
                    if (running) {
                        Button(
                            onClick = { container.overlay.stop() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.status.criticalContainer,
                                contentColor = MaterialTheme.status.onCriticalContainer
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Stop,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Text("Stop", modifier = Modifier.padding(start = 6.dp))
                        }
                    } else {
                        FilledTonalButton(
                            onClick = {
                                if (canDraw) container.overlay.start() else onRequestOverlayPermission()
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Text("Start", modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }

                if (!canDraw) {
                    Spacer(Modifier.height(Space.md))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.status.warnContainer)
                            .padding(Space.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.status.warn,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "The \"Display over other apps\" permission is missing.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.status.onWarnContainer,
                            modifier = Modifier.padding(start = Space.sm)
                        )
                    }
                }
            }
        }

        item { SectionHeader("Preview") }

        item {
            HudPreview(
                fpsText = if (fps.valid) String.format("%.0f", fps.fps) else "--",
                frameText = if (fps.valid) "${String.format("%.1f", fps.frameTimeMs)} ms" else "",
                cpuText = system.cpuPercent?.let { "CPU ${it.toInt()}%" } ?: "",
                history = fps.history,
                style = style,
                rows = rows.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    .map { it to overlayRowValue(it, fps, system) },
                showGraph = showGraph,
                threshold = threshold
            )
        }

        item { SectionHeader("Style") }

        item {
            Panel {
                SegmentedOptions(
                    options = STYLE_LABELS,
                    selected = style.lowercase().replaceFirstChar { it.uppercase() },
                    onSelect = { picked ->
                        val enumValue = OverlayStyle.entries.firstOrNull {
                            it.name.equals(picked, ignoreCase = true)
                        }
                        settings.overlayStyle.value = (enumValue ?: OverlayStyle.CLASSIC).name
                    }
                )
                Text(
                    text = "Pill is the small in-game meter: one number, one unit. " +
                        "Compact shows only the frame rate, Classic adds frame time, " +
                        "Detailed shows every row, RTSS uses an Afterburner style block.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Space.md)
                )
            }
        }

        item { SectionHeader("Rows") }

        item {
            Panel {
                ChipRow(
                    options = ALL_ROWS,
                    selected = rows.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
                    onToggle = { option ->
                        val current = rows.split(",").map { it.trim() }
                            .filter { it.isNotEmpty() }.toMutableSet()
                        if (!current.add(option)) current.remove(option)
                        settings.overlayRows.value = current.joinToString(",")
                    }
                )
            }
        }

        item { SectionHeader("Appearance") }

        item {
            Panel {
                ValueSlider(
                    label = "Opacity",
                    valueText = "${(opacity * 100).toInt()}%",
                    value = opacity,
                    onValueChange = { settings.overlayOpacity.value = it },
                    valueRange = 0.3f..1f
                )
                ValueSlider(
                    label = "Scale",
                    valueText = String.format(Locale.US, "%.1fx", scale),
                    value = scale,
                    onValueChange = { settings.overlayScale.value = it },
                    valueRange = 0.7f..1.6f
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Show graph",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Draws the frame rate curve inside the overlay.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = showGraph,
                        onCheckedChange = { settings.overlayShowGraph.value = it }
                    )
                }
                if (showGraph) {
                    ValueSlider(
                        label = "Graph height",
                        valueText = "$graphHeight px",
                        value = graphHeight.toFloat(),
                        onValueChange = { settings.overlayGraphHeight.value = it.toInt() },
                        valueRange = 24f..140f
                    )
                }
            }
        }
    }
}

/**
 * A faithful mock of the floating widget, so what the user configures here is what they get
 * on top of the game.
 */
@Composable
private fun HudPreview(
    fpsText: String,
    frameText: String,
    cpuText: String,
    history: List<Float>,
    style: String,
    rows: List<Pair<String, String>>,
    showGraph: Boolean,
    threshold: Int
) {
    if (style.uppercase() == "PILL") {
        PillPreview(fpsText = fpsText, frameText = frameText)
        return
    }

    val expanded = style.uppercase() != "COMPACT"
    val graphVisible = showGraph && style.uppercase() != "RTSS"

    Panel {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .background(HudBackground)
                .padding(Space.md)
        ) {
            Column {
                if (style.uppercase() == "RTSS" || style.uppercase() == "DETAILED") {
                    Text(
                        text = "FRAMESCOPE",
                        style = MonoBlock.copy(fontSize = 9.sp, letterSpacing = 1.sp),
                        color = HudMuted
                    )
                    Spacer(Modifier.height(Space.xs))
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = fpsText,
                        style = MonoBlock.copy(
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-1).sp
                        ),
                        color = HudGood
                    )
                    Spacer(Modifier.width(Space.sm))
                    Text(
                        text = frameText,
                        style = MonoBlock.copy(fontSize = 11.sp),
                        color = HudText,
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                    Spacer(Modifier.weight(1f))
                    if (cpuText.isNotEmpty()) {
                        Text(
                            text = cpuText,
                            style = MonoBlock.copy(fontSize = 10.sp),
                            color = HudMuted
                        )
                    }
                }
                if (expanded && graphVisible) {
                    Spacer(Modifier.height(Space.xs))
                    Sparkline(
                        values = history,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                        color = HudGood,
                        target = threshold.toFloat(),
                        trackColor = androidx.compose.ui.graphics.Color(0xFF1E2733),
                        gridColor = androidx.compose.ui.graphics.Color(0xFF2C3846)
                    )
                }
                if (expanded && style.uppercase() != "COMPACT") {
                    Spacer(Modifier.height(Space.xs))
                    rows.take(6).forEach { (label, value) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                            Text(
                                text = label,
                                style = MonoBlock.copy(fontSize = 9.sp),
                                color = HudMuted,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = value,
                                style = MonoBlock.copy(fontSize = 11.sp),
                                color = HudText
                            )
                        }
                    }
                }
            }
        }
        Text(
            text = "Preview of the floating widget",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Space.sm)
        )
    }
}

/**
 * The pill, drawn the way the widget draws it: one number, one unit, and the
 * frame time once the widget is expanded.
 */
@Composable
private fun PillPreview(fpsText: String, frameText: String) {
    Panel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(HudBackground)
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = fpsText,
                        style = MonoBlock.copy(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.5).sp
                        ),
                        color = HudGood
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = "FPS",
                        style = MonoBlock.copy(fontSize = 8.sp),
                        color = HudMuted,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                    if (frameText.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = frameText,
                            style = MonoBlock.copy(fontSize = 10.sp),
                            color = HudMuted,
                            modifier = Modifier.padding(bottom = 1.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.width(Space.md))
            Text(
                text = "Drag it anywhere. Tap it to show or hide the frame time.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = "Preview of the floating widget",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Space.sm)
        )
    }
}

/** The same values the floating widget shows, so the preview cannot drift from it. */
private fun overlayRowValue(
    key: String,
    fps: com.framescope.monitor.core.FpsState,
    system: com.framescope.monitor.core.SystemMetrics
): String = when (key.uppercase()) {
    "FPS" -> if (fps.valid) String.format("%.0f", fps.fps) else "--"
    "FRAME" -> if (fps.valid) "${String.format("%.1f", fps.frameTimeMs)} ms" else "--"
    "CPU" -> system.cpuPercent?.let { "${it.toInt()}%" } ?: "--"
    "CPUF" -> system.cpuFreqMHz?.let { "$it MHz" } ?: "--"
    "GPU" -> system.gpuFreqMHz?.let { "$it MHz" } ?: system.gpuName?.let { it.take(14) } ?: "--"
    "TEMP" -> system.thermalMaxC?.let { "${it.toInt()}°" } ?: "--"
    "BAT" -> system.batteryLevel?.let { "$it%" } ?: "--"
    "POWER" -> system.powerWatts?.let { "${String.format("%.2f", it)} W" } ?: "--"
    "MEM" -> system.ramPercent?.let { "$it%" } ?: "--"
    "REFRESH" -> system.refreshHz?.let { "${it.toInt()} Hz" } ?: "--"
    "APP" -> fps.surface?.substringAfterLast('/')?.substringBefore('#') ?: "--"
    else -> "--"
}

private val HudBackground = androidx.compose.ui.graphics.Color(0xF00B0F14)
private val HudText = androidx.compose.ui.graphics.Color(0xFFE2E8F0)
private val HudMuted = androidx.compose.ui.graphics.Color(0xFF7DD3FC)
private val HudGood = androidx.compose.ui.graphics.Color(0xFF4ADE80)
