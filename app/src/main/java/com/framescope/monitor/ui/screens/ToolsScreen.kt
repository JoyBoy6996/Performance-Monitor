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
import androidx.compose.material3.HorizontalDivider
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
import android.content.Context
import android.os.Build
import com.framescope.monitor.AppContainer
import com.framescope.monitor.core.SystemMetrics
import com.framescope.monitor.core.deviceTemperatureC
import com.framescope.monitor.core.thermalStatusLabel
import com.framescope.monitor.metrics.NativeCollectors
import com.framescope.monitor.shell.ShellHub
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

/** Device facts that are safe to show even when a shell reading is unavailable. */
private data class DeviceFacts(
    val platform: String? = null,
    val soc: String? = null,
    val vendor: String? = null,
    val cpu: String? = null,
    val battery: NativeCollectors.BatteryReading? = null,
    val health: String? = null,
    val technology: String? = null,
    val cycles: String? = null,
    val status: String? = null,
    val storage: String? = null,
    val display: String? = null
)

/**
 * Reads what the phone is willing to say. Every block is guarded, so a property
 * that does not exist shows "--" instead of taking the screen down.
 */
private suspend fun collectDeviceFacts(context: Context, hub: ShellHub): DeviceFacts {
    val props = runCatching {
        hub.execOrNull(
            "echo platform=\$(getprop ro.board.platform); " +
                "echo soc=\$(getprop ro.soc.model); " +
                "echo vendor=\$(getprop ro.soc.manufacturer)"
        )?.out.orEmpty()
    }.getOrDefault("")

    fun prop(key: String): String? = props.lineSequence()
        .firstOrNull { it.startsWith("$key=") }
        ?.substringAfter('=')
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    val cpu = runCatching {
        hub.execOrNull("cat /proc/cpuinfo 2>/dev/null | grep -m1 -E 'Hardware|Processor|model name'")
            ?.out?.lineSequence()?.firstOrNull { it.isNotBlank() }
            ?.substringAfter(':')?.trim()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    val batteryDump = runCatching {
        hub.execOrNull("dumpsys battery 2>/dev/null | head -30")?.out.orEmpty()
    }.getOrDefault("")

    fun batteryField(vararg keys: String): String? = batteryDump.lineSequence()
        .map { it.trim() }
        .firstOrNull { line -> keys.any { line.startsWith(it, ignoreCase = true) } }
        ?.substringAfter(':')?.trim()?.takeIf { it.isNotBlank() }

    fun health(value: String?): String? = when (value) {
        "2" -> "Good"
        "3" -> "Overheat"
        "4" -> "Dead"
        "5" -> "Overvoltage"
        "7" -> "Cold"
        else -> value
    }

    fun status(value: String?): String? = when (value) {
        "2" -> "Charging"
        "3" -> "Discharging"
        "4" -> "Full"
        "5" -> "Not charging"
        else -> value
    }

    val battery = runCatching { NativeCollectors(context).battery().value }.getOrNull()

    val storage = runCatching {
        val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
        val gb = 1024f * 1024f * 1024f
        val total = stat.blockCountLong * stat.blockSizeLong / gb
        val free = stat.availableBlocksLong * stat.blockSizeLong / gb
        String.format(java.util.Locale.US, "%.1f / %.1f GB free", free, total)
    }.getOrNull()

    val metrics = context.resources.displayMetrics
    val display = "${metrics.widthPixels} x ${metrics.heightPixels} · ${metrics.densityDpi} dpi"

    return DeviceFacts(
        platform = prop("platform"),
        soc = prop("soc"),
        vendor = prop("vendor"),
        cpu = cpu,
        battery = battery,
        health = health(batteryField("health")),
        technology = batteryField("technology"),
        cycles = batteryField("cycle", "charge counter"),
        status = status(batteryField("status")),
        storage = storage,
        display = display
    )
}

@Composable
fun ToolsScreen(
    container: AppContainer,
    bottomPadding: Dp = 24.dp
) {
    var showScreenTest by remember { mutableStateOf(false) }
    var deviceFacts by remember { mutableStateOf(DeviceFacts()) }
    var consoleInput by remember { mutableStateOf("dumpsys battery") }
    var consoleOutput by remember { mutableStateOf("") }
    var diagnosticsOutput by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val system by container.engine.system.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        deviceFacts = collectDeviceFacts(context, container.hub)
    }

    FrameScopeScreen(
        title = "Tools",
        subtitle = "Device facts and a privileged shell",
        bottomPadding = bottomPadding
    ) {
        item { SectionHeader("Device") }

        item { DevicePanel(facts = deviceFacts, system = system) }

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
                            text = "Full bleed colour patches and a touch trail for dead pixels, " +
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
                                    "Shizuku is not connected. Please start Shizuku and grant permission."
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
                            com.framescope.monitor.ui.Diagnostics.copy(
                                context,
                                diagnosticsOutput.lines().joinToString("\n") { line ->
                                    if (line.isBlank()) "#" else "# $line"
                                }
                            )
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
                    text = "Run any command with elevated Shizuku shell privileges.",
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
                                    "Shizuku is not connected. Please start Shizuku and grant permission."
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
private fun DevicePanel(facts: DeviceFacts, system: SystemMetrics) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val warn = MaterialTheme.status.warn

    Panel {
        @Composable
        fun divider() {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = Space.sm),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }

        Text("Identity", style = MaterialTheme.typography.titleSmall, color = onSurface)
        MetricLine("Device", "${Build.MANUFACTURER} ${Build.MODEL}", onSurface)
        MetricLine("Brand", Build.BRAND, onSurface)
        MetricLine("Codename", Build.DEVICE, onSurface)
        MetricLine("Product", Build.PRODUCT, onSurface)
        MetricLine("Board", Build.BOARD, onSurface)
        MetricLine("Hardware", Build.HARDWARE, onSurface)
        MetricLine("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", onSurface)
        MetricLine(
            "Architecture",
            Build.SUPPORTED_ABIS.joinToString(", ").ifBlank { "--" },
            onSurface
        )
        MetricLine("Build", Build.FINGERPRINT.take(64), muted)

        divider()
        Text("Processor", style = MaterialTheme.typography.titleSmall, color = onSurface)
        facts.platform?.let { MetricLine("Platform", it, onSurface) }
        facts.vendor?.let { MetricLine("SoC vendor", it, onSurface) }
        facts.soc?.let { MetricLine("SoC model", it, onSurface) }
        facts.cpu?.let { MetricLine("CPU", it.take(48), onSurface) }
        MetricLine("CPU clock", system.cpuFreqMHz?.let { "$it MHz" } ?: "--", onSurface)
        system.cpuPercent?.let {
            MetricLine("CPU load", String.format(java.util.Locale.US, "%.0f%%", it), onSurface)
        }
        MetricLine("GPU", system.gpuName?.take(34) ?: "--", onSurface)
        MetricLine("GPU clock", system.gpuFreqMHz?.let { "$it MHz" } ?: "--", onSurface)

        divider()
        Text("Memory and display", style = MaterialTheme.typography.titleSmall, color = onSurface)
        MetricLine(
            "RAM",
            system.ramTotalGb?.let { total ->
                "${String.format("%.1f", system.ramUsedGb ?: 0f)} / ${String.format("%.1f", total)} GB"
            } ?: "--",
            onSurface
        )
        MetricLine("Storage", facts.storage ?: "--", onSurface)
        MetricLine("Resolution", facts.display ?: "--", onSurface)
        MetricLine(
            "Refresh rate",
            system.refreshHz?.let { "${it.toInt()} Hz" } ?: "--",
            warn
        )

        divider()
        Text("Battery", style = MaterialTheme.typography.titleSmall, color = onSurface)
        MetricLine("Level", facts.battery?.levelPercent?.let { "$it%" } ?: "--", onSurface)
        facts.status?.let { MetricLine("Status", it, onSurface) }
        facts.health?.let { MetricLine("Health", it, onSurface) }
        facts.technology?.let { MetricLine("Technology", it, onSurface) }
        facts.cycles?.let { MetricLine("Cycles", it, onSurface) }
        MetricLine(
            "Temperature",
            facts.battery?.temperatureC?.let { String.format("%.1f°C", it) } ?: "--",
            warn
        )
        MetricLine("Voltage", facts.battery?.voltageMilliVolts?.let { "$it mV" } ?: "--", onSurface)
        MetricLine(
            "Power",
            facts.battery?.watts?.let { String.format("%.2f W", it) } ?: "--",
            onSurface
        )

        divider()
        Text("Thermal", style = MaterialTheme.typography.titleSmall, color = onSurface)
        MetricLine(
            "Phone temperature",
            system.deviceTemperatureC()?.let { String.format("%.1f°C", it) } ?: "--",
            warn
        )
        MetricLine(
            "State",
            system.thermalStatus?.let { thermalStatusLabel(it) } ?: "--",
            if ((system.thermalStatus ?: 0) > 0) warn else muted
        )
        MetricLine(
            "Hottest sensor",
            system.thermalMaxC?.let { String.format("%.1f°C", it) } ?: "--",
            muted
        )
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
                        "Shizuku is not connected. Please start Shizuku and grant permission."
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
