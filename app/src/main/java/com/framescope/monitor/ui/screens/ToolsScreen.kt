package com.framescope.monitor.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.framescope.monitor.AppContainer
import com.framescope.monitor.core.SystemMetrics
import com.framescope.monitor.ui.FrameScopeScreen
import com.framescope.monitor.ui.components.MetricLine
import com.framescope.monitor.ui.components.MonoField
import com.framescope.monitor.ui.components.Panel
import com.framescope.monitor.ui.components.SectionHeader
import com.framescope.monitor.ui.components.TerminalBlock
import com.framescope.monitor.ui.theme.MonoBlock
import com.framescope.monitor.ui.theme.Space
import kotlinx.coroutines.launch

/** Everything needed to see why the frame data looks the way it does. */
private val DIAGNOSTIC_COMMAND = com.framescope.monitor.ui.Diagnostics.command

@Composable
fun ToolsScreen(
    container: AppContainer,
    bottomPadding: Dp = 24.dp
) {
    var showScreenTest by remember { mutableStateOf(false) }
    var deviceInfo by remember { mutableStateOf<List<String>>(emptyList()) }
    var consoleInput by remember { mutableStateOf("dumpsys battery") }
    var consoleOutput by remember { mutableStateOf("") }
    var diagnosticsOutput by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val system by container.engine.system.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        val result = container.hub.execOrNull(
            "getprop ro.product.model; getprop ro.board.platform; getprop ro.hardware; " +
                "getprop ro.build.version.release; getprop ro.build.version.sdk"
        )
        deviceInfo = result?.out.orEmpty().lines().filter { it.isNotBlank() }
    }

    FrameScopeScreen(
        title = "Tools",
        subtitle = "Device facts and a privileged shell",
        bottomPadding = bottomPadding
    ) {
        item { SectionHeader("Device") }

        item { DevicePanel(deviceInfo = deviceInfo, system = system) }

        item { SectionHeader("Screen test") }

        item {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Tv,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Column(Modifier.padding(start = Space.md).weight(1f)) {
                        Text(
                            text = "Colour and touch test",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Full bleed colour patches and a touch trail to spot dead pixels, " +
                                "banding and ghosting.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Spacer(Modifier.width(Space.md))
                    FilledTonalButton(onClick = { showScreenTest = true }) {
                        Text("Open")
                    }
                }
            }
        }

        item { SectionHeader("Process monitor") }

        item {
            CommandPanel(
                container = container,
                title = "Top processes",
                description = "Snapshot of the processes using the most memory and CPU.",
                command = "top -b -n 1 -o RES,%CPU 2>/dev/null | head -16"
            )
        }

        item { SectionHeader("Thermal sensors") }

        item {
            CommandPanel(
                container = container,
                title = "Thermalservice",
                description = "Every temperature sensor the system exposes, with its current value.",
                command = "dumpsys thermalservice 2>/dev/null | grep -E 'Temperature\\{|mName=' | head -24"
            )
        }

        item { SectionHeader("Power and battery") }

        item {
            CommandPanel(
                container = container,
                title = "Battery",
                description = "Charge level, health, current draw, voltage and charge cycles.",
                command = "dumpsys battery 2>/dev/null"
            )
        }

        item { SectionHeader("Measurement diagnostics") }

        item {
            Panel {
                Text(
                    text = "Collects what FrameScope sees when it looks for frame data, so a " +
                        "wrong reading can be traced back to the source.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Space.md),
                    horizontalArrangement = Arrangement.End
                ) {
                    FilledTonalButton(
                        enabled = !running,
                        onClick = {
                            running = true
                            diagnosticsOutput = "Collecting..."
                            scope.launch {
                                val result = container.hub.execOrNull(DIAGNOSTIC_COMMAND)
                                diagnosticsOutput = if (result == null) {
                                    "No privileged backend available. Run the setup first."
                                } else {
                                    (result.out + result.err).trim()
                                }
                                running = false
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Text("Collect", modifier = Modifier.padding(start = 6.dp))
                    }
                    OutlinedButton(
                        enabled = !running && diagnosticsOutput.isNotBlank(),
                        onClick = {
                            com.framescope.monitor.ui.Diagnostics.copy(context, diagnosticsOutput)
                            diagnosticsOutput = diagnosticsOutput + "\n\n[copied to the clipboard]"
                        }
                    ) {
                        Text("Copy")
                    }
                }
                if (diagnosticsOutput.isNotEmpty()) {
                    Spacer(Modifier.height(Space.md))
                    TerminalBlock(text = diagnosticsOutput, maxLines = 30)
                }
            }
        }

        item { SectionHeader("Shell console") }

        item {
            Panel {
                Text(
                    text = "Run any command with the privileged backend that is currently active.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                MonoField(
                    value = consoleInput,
                    onValueChange = { consoleInput = it },
                    label = "Command",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Space.md)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Space.sm),
                    horizontalArrangement = Arrangement.End
                ) {
                    FilledTonalButton(
                        enabled = !running,
                        onClick = {
                            running = true
                            consoleOutput = "Running..."
                            scope.launch {
                                val result = container.hub.execOrNull(consoleInput.trim())
                                consoleOutput = if (result == null) {
                                    "No privileged backend available. Run the setup first."
                                } else {
                                    (result.out + result.err).trim().ifEmpty { "exit code ${result.code}" }
                                }
                                running = false
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Text("Run", modifier = Modifier.padding(start = 6.dp))
                    }
                }
                if (consoleOutput.isNotEmpty()) {
                    Spacer(Modifier.height(Space.md))
                    TerminalBlock(text = consoleOutput, maxLines = 20)
                }
            }
        }
    }

    if (showScreenTest) {
        ScreenTestDialog(onDismiss = { showScreenTest = false })
    }
}

@Composable
private fun DevicePanel(deviceInfo: List<String>, system: SystemMetrics) {
    Panel {
        val labels = listOf("Model", "Platform", "Hardware", "Android", "API level")
        deviceInfo.forEachIndexed { index, value ->
            MetricLine(
                label = labels.getOrElse(index) { "Info" },
                value = value,
                accent = MaterialTheme.colorScheme.onSurface
            )
        }
        system.gpuName?.let {
            MetricLine(
                label = "GPU",
                value = it.take(34),
                accent = MaterialTheme.colorScheme.onSurface
            )
        }
        system.refreshHz?.let {
            MetricLine(
                label = "Refresh rate",
                value = "${it.toInt()} Hz",
                accent = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun CommandPanel(
    container: AppContainer,
    title: String,
    description: String,
    command: String
) {
    var output by remember { mutableStateOf("Not run yet") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Panel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = {
                scope.launch {
                    busy = true
                    val result = container.hub.execOrNull(command)
                    output = if (result == null) {
                        "No privileged backend available. Run the setup first."
                    } else {
                        (result.out + result.err).trim().ifEmpty { "no output" }
                    }
                    busy = false
                }
            }, modifier = Modifier.size(34.dp)) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "Run $title",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
        Spacer(Modifier.height(Space.md))
        TerminalBlock(text = output, maxLines = 20)
    }
}

@Composable
private fun ScreenTestDialog(onDismiss: () -> Unit) {
    val dots = remember { mutableStateListOf<Offset>() }
    val colors = listOf(
        Color.Black, Color.White, Color.Red, Color.Green, Color.Blue, Color.Yellow,
        Color.Cyan, Color.Magenta, Color(0xFF808080), Color(0xFF00FF00)
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { dots.add(it) },
                        onDrag = { change, _ -> dots.add(change.position) }
                    )
                }
                .padding(6.dp)
        ) {
            Row(Modifier.fillMaxWidth().weight(1f)) {
                colors.forEach { color ->
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .background(color)
                    )
                }
            }
            Row(Modifier.fillMaxWidth().height(64.dp)) {
                colors.drop(5).forEach { color ->
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .background(color)
                    )
                }
            }
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(180.dp)
            ) {
                dots.forEach { dot ->
                    drawCircle(
                        color = Color.White.copy(alpha = 0.9f),
                        radius = 9f,
                        center = dot
                    )
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.6f),
                        radius = 9f,
                        center = dot,
                        style = Stroke(width = 2f)
                    )
                }
            }
            androidx.compose.material3.TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) { Text("Close", color = Color.White) }
        }
    }
}
