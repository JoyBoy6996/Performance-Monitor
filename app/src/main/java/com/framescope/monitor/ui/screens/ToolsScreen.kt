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
import com.framescope.monitor.core.finiteOrNull
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
import com.framescope.monitor.ui.theme.status
import kotlinx.coroutines.launch

/** Everything needed to see why the frame data looks the way it does. */
private val DIAGNOSTIC_COMMAND = com.framescope.monitor.ui.Diagnostics.command

/**
 * Marketing names for the model codes Android reports in Build.MODEL.
 * An unknown code falls back to manufacturer + model, so no phone is misnamed.
 */
private val DEVICE_NAMES = mapOf(
    "I2405" to "iQOO Neo 10"
)

/** Standard RAM sizes a phone is sold with, in GiB. */
private val RAM_VARIANTS_GB = intArrayOf(2, 3, 4, 6, 8, 10, 12, 16, 18, 20, 24, 32)

/** Standard storage sizes a phone is sold with, in decimal GB. */
private val STORAGE_VARIANTS_GB = intArrayOf(16, 32, 64, 128, 256, 512, 1024)

/**
 * Android reports usable capacity, not the number on the box: a 12 GB phone
 * commonly reports 11.x GiB and a 256 GB phone about 236 GB because the system
 * reserves part of the chip. Map the real reading to the nearest standard
 * variant instead of showing the usable number as if it were the variant.
 *
 * The raw reading is still shown in the RAM and Storage rows below.
 */
private fun variantSize(rawGb: Float?, sizes: IntArray): String? {
    val raw = rawGb?.finiteOrNull()?.takeIf { it > 0f } ?: return null
    // Smallest marketed size whose usable capacity could look this big.
    sizes.firstOrNull { size -> raw <= size && raw >= size * 0.82f }?.let { return it.toString() }
    // A reading above every known size, or between two of them, still gets the
    // closest standard size. Ties go to the larger one.
    var best = sizes.first()
    for (size in sizes) {
        val gap = kotlin.math.abs(size - raw)
        val bestGap = kotlin.math.abs(best - raw)
        if (gap < bestGap || (gap == bestGap && size > best)) best = size
    }
    return best.toString()
}

/**
 * Chipset IDs Android exposes through ro.soc.model, translated to the names people
 * know. An ID that is not listed is shown as-is; nothing is guessed from a GPU name.
 */
private val CHIPSET_NAMES = mapOf(
    "SM8735" to "Snapdragon 8s Gen 4",
    "SM8750" to "Snapdragon 8 Elite",
    "SM8650" to "Snapdragon 8 Gen 3",
    "SM8635" to "Snapdragon 8s Gen 3",
    "SM8550" to "Snapdragon 8 Gen 2",
    "SM8475" to "Snapdragon 8+ Gen 1",
    "SM8450" to "Snapdragon 8 Gen 1",
    "SM8350" to "Snapdragon 888+",
    "SM8250" to "Snapdragon 865",
    "SM8150" to "Snapdragon 855",
    "SDM845" to "Snapdragon 845",
    "SDM660" to "Snapdragon 660",
    "SM7325" to "Snapdragon 778G",
    "SM7125" to "Snapdragon 720G",
    "SM6375" to "Snapdragon 695",
    "SM6150" to "Snapdragon 675",
    "SM6125" to "Snapdragon 665",
    "SM4350" to "Snapdragon 480",
    "SM4250" to "Snapdragon 460",
    "MT6989" to "Dimensity 9300",
    "MT6985" to "Dimensity 9200",
    "MT6983" to "Dimensity 9000",
    "MT6893" to "Dimensity 8000",
    "MT6891Z" to "Dimensity 1100",
    "MT6889Z" to "Dimensity 1000+",
    "MT6877" to "Dimensity 900",
    "MT6873" to "Dimensity 800",
    "MT6833" to "Dimensity 700",
    "EXYNOS2400" to "Exynos 2400",
    "EXYNOS2200" to "Exynos 2200",
    "EXYNOS2100" to "Exynos 2100",
    "EXYNOS990" to "Exynos 990",
    "EXYNOS9820" to "Exynos 9820",
    "EXYNOS9810" to "Exynos 9810",
    "KIRIN9000S" to "Kirin 9000S",
    "KIRIN9000" to "Kirin 9000",
    "KIRIN990" to "Kirin 990",
    "KIRIN980" to "Kirin 980",
    "KIRIN970" to "Kirin 970"
)

/** Turns a raw SoC ID into a readable processor name, on every brand. */
private fun chipsetDisplayName(
    soc: String?,
    vendor: String?,
    platform: String?,
    chipset: String?
): String? {
    val raw = listOf(chipset, soc, platform)
        .mapNotNull { it?.trim()?.takeIf { it.isNotBlank() } }
    val marketing = Regex(
        "snapdragon|dimensity|exynos|tensor|kirin|helio",
        RegexOption.IGNORE_CASE
    )
    val direct = raw.firstOrNull { marketing.containsMatchIn(it) }
    if (direct != null) return direct.replace(Regex("\\s+"), " ").take(48)

    val id = raw.map { it.uppercase(java.util.Locale.US) }
        .mapNotNull { text ->
            Regex(
                "(SM[0-9]{4}[A-Z-]*|SDM[0-9]{4}|MSM[0-9]{4}|" +
                    "MT[0-9]{4}[A-Z]*|EXYNOS[0-9]{4}|KIRIN[0-9]{3,4})"
            ).find(text)?.value
        }
        .firstOrNull()
        ?.let { if (it.startsWith("SM") && it.length > 6) it.take(6) else it }
        ?: return raw.firstOrNull()?.take(48)

    return CHIPSET_NAMES[id] ?: id
}

/** Device facts that are safe to show even when a shell reading is unavailable. */
private data class DeviceFacts(
    val platform: String? = null,
    val soc: String? = null,
    val vendor: String? = null,
    val chipset: String? = null,
    val processor: String? = null,
    val cpu: String? = null,
    val battery: NativeCollectors.BatteryReading? = null,
    val health: String? = null,
    val technology: String? = null,
    val cycles: String? = null,
    val status: String? = null,
    val storageFreeGb: Float? = null,
    val storageTotalGb: Float? = null,
    val display: String? = null,
    val kernel: String? = null
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
                "echo vendor=\$(getprop ro.soc.manufacturer); " +
                "echo chipset=\$(getprop ro.chipset)"
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

    fun health(value: Int?): String? = when (value) {
        null -> null
        1 -> "Unknown"
        2 -> "Good"
        3 -> "Overheat"
        4 -> "Dead"
        5 -> "Overvoltage"
        6 -> "Unspecified failure"
        7 -> "Cold"
        else -> value.toString()
    }

    fun status(value: Int?): String? = when (value) {
        null -> null
        2 -> "Charging"
        3 -> "Discharging"
        4 -> "Full"
        5 -> "Not charging"
        else -> value.toString()
    }

    val battery = runCatching { NativeCollectors(context).battery().value }.getOrNull()

    // "charge counter" is remaining charge in µAh, not a cycle count. Use the
    // public API first, then the fuel-gauge nodes a device may expose. An absent
    // cycle count stays "--" instead of being invented from charge counter.
    val cycleDump = runCatching {
        hub.execOrNull(
            "for f in /sys/class/power_supply/battery/cycle_count " +
                "/sys/class/power_supply/battery/battery_cycle " +
                "/sys/class/power_supply/battery/cycles; do " +
                "[ -r \"\$f\" ] && cat \"\$f\" && break; done"
        )?.out.orEmpty()
    }.getOrDefault("")
    val cycles = listOf(
        battery?.cycleCount?.toString(),
        cycleDump.lineSequence().firstOrNull { it.isNotBlank() }?.trim(),
        batteryField("cycle_count", "cycles", "cycle")
    ).mapNotNull { raw ->
        raw?.toIntOrNull()?.takeIf { it in 0..10_000 }
    }.firstOrNull()?.toString()

    // User-visible internal storage is what "256 GB" means. /data is a different
    // partition on some phones, so prefer the shared storage path reported to users.
    val storage = runCatching {
        val path = android.os.Environment.getExternalStorageDirectory().path
        val stat = android.os.StatFs(path)
        val gb = 1_000_000_000f
        Pair(
            stat.availableBlocksLong * stat.blockSizeLong / gb,
            stat.blockCountLong * stat.blockSizeLong / gb
        )
    }.getOrNull()

    // getRealMetrics reports the panel, not the window minus system bars.
    val realMetrics = android.util.DisplayMetrics()
    runCatching {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE)
            as? android.hardware.display.DisplayManager
        @Suppress("DEPRECATION")
        manager?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.getRealMetrics(realMetrics)
    }
    val display = if (realMetrics.widthPixels > 0 && realMetrics.heightPixels > 0) {
        "${realMetrics.widthPixels} x ${realMetrics.heightPixels} · ${realMetrics.densityDpi} dpi"
    } else null

    val kernel = runCatching {
        hub.execOrNull("uname -r 2>/dev/null")?.out?.lineSequence()
            ?.firstOrNull { it.isNotBlank() }?.trim()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    val officialSoc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Build.SOC_MODEL.takeIf { it.isNotBlank() && !it.equals("unknown", true) }
    } else null
    val officialVendor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Build.SOC_MANUFACTURER.takeIf { it.isNotBlank() && !it.equals("unknown", true) }
    } else null

    return DeviceFacts(
        platform = prop("platform"),
        soc = officialSoc ?: prop("soc"),
        vendor = officialVendor ?: prop("vendor"),
        chipset = prop("chipset"),
        processor = chipsetDisplayName(
            officialSoc ?: prop("soc"),
            officialVendor ?: prop("vendor"),
            prop("platform"),
            prop("chipset")
        ),
        cpu = cpu,
        battery = battery,
        health = battery?.health?.let { health(it) } ?: health(batteryField("health")),
        technology = battery?.technology ?: batteryField("technology"),
        cycles = cycles,
        status = battery?.status?.let { status(it) } ?: status(batteryField("status")),
        storageFreeGb = storage?.first,
        storageTotalGb = storage?.second,
        display = display,
        kernel = kernel
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
                                    "No privileged shell is connected. Start Shizuku or set up the local ADB bridge."
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
                                    "No privileged shell is connected. Start Shizuku or set up the local ADB bridge."
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
    val good = MaterialTheme.status.good

    Panel {
        @Composable
        fun divider() {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = Space.sm),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }

        Text("Identity", style = MaterialTheme.typography.titleSmall, color = onSurface)
        val modelCode = Build.MODEL.trim().ifBlank { "--" }
        val friendlyName = DEVICE_NAMES[modelCode.uppercase(java.util.Locale.US)]
            ?: listOf(Build.BRAND, Build.MODEL)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(" ")
                .ifBlank { modelCode }
        val modelLine = if (modelCode == "--") {
            "--"
        } else if (modelCode.startsWith(Build.MANUFACTURER, ignoreCase = true)) {
            modelCode
        } else {
            "${Build.MANUFACTURER} $modelCode"
        }
        MetricLine("Device", friendlyName, onSurface)
        MetricLine("Model", modelLine, onSurface)
        MetricLine("Brand", Build.BRAND, onSurface)
        MetricLine("Codename", Build.DEVICE, onSurface)
        MetricLine("Product", Build.PRODUCT, onSurface)
        MetricLine("Board", Build.BOARD, onSurface)
        MetricLine("Hardware", Build.HARDWARE, onSurface)
        MetricLine("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", onSurface)
        MetricLine("Build ID", Build.ID, onSurface)
        facts.kernel?.let { MetricLine("Kernel", it, muted) }
        MetricLine(
            "Architecture",
            Build.SUPPORTED_ABIS.joinToString(", ").ifBlank { "--" },
            onSurface
        )
        MetricLine("Build", Build.FINGERPRINT.take(64), muted)

        divider()
        Text("Processor", style = MaterialTheme.typography.titleSmall, color = onSurface)
        MetricLine("Processor", facts.processor ?: facts.soc ?: "--", onSurface)
        facts.soc?.takeIf { it != facts.processor }?.let {
            MetricLine("SoC ID", it, muted)
        }
        facts.vendor?.let { MetricLine("SoC vendor", it, muted) }
        facts.platform?.let { MetricLine("Platform", it, muted) }
        facts.cpu?.let { MetricLine("CPU", it.take(48), onSurface) }
        MetricLine("CPU clock (fastest)", system.cpuFreqMHz?.let { "$it MHz" } ?: "--", onSurface)
        MetricLine(
            "CPU load",
            system.cpuPercent.finiteOrNull()?.let { String.format(java.util.Locale.US, "%.0f%%", it) } ?: "--",
            onSurface
        )
        MetricLine("GPU", system.gpuName?.take(34) ?: "--", onSurface)
        MetricLine("GPU clock", system.gpuFreqMHz?.let { "$it MHz" } ?: "--", onSurface)

        divider()
        Text("Memory and display", style = MaterialTheme.typography.titleSmall, color = onSurface)
        val ramMarketing = variantSize(system.ramTotalGb.finiteOrNull(), RAM_VARIANTS_GB)
        val storageMarketing = variantSize(facts.storageTotalGb.finiteOrNull(), STORAGE_VARIANTS_GB)
        val variant = when {
            ramMarketing != null && storageMarketing != null ->
                "$ramMarketing GB + $storageMarketing GB"
            ramMarketing != null -> "$ramMarketing GB RAM"
            storageMarketing != null -> "$storageMarketing GB storage"
            else -> "--"
        }
        MetricLine("Variant", variant, onSurface)
        Text(
            text = "Nearest standard size; RAM and Storage show usable capacity",
            style = MaterialTheme.typography.labelSmall,
            color = muted
        )
        MetricLine(
            "RAM",
            system.ramTotalGb.finiteOrNull()?.let { total ->
                "${String.format("%.1f", system.ramUsedGb.finiteOrNull() ?: 0f)} / ${String.format("%.1f", total)} GB"
            } ?: "--",
            onSurface
        )
        MetricLine(
            "Storage",
            if (facts.storageFreeGb.finiteOrNull() != null && facts.storageTotalGb.finiteOrNull() != null) {
                String.format(
                    java.util.Locale.US,
                    "%.1f / %.0f GB free",
                    facts.storageFreeGb,
                    facts.storageTotalGb
                )
            } else "--",
            onSurface
        )
        MetricLine("Resolution", facts.display ?: "--", onSurface)
        MetricLine(
            "Refresh rate",
            system.refreshHz.finiteOrNull()?.let { "${it.toInt()} Hz" } ?: "--",
            warn
        )

        divider()
        Text("Battery", style = MaterialTheme.typography.titleSmall, color = onSurface)
        MetricLine("Level", facts.battery?.levelPercent?.let { "$it%" } ?: "--", onSurface)
        facts.status?.let { MetricLine("Status", it, onSurface) }
        facts.health?.let { MetricLine("Health", it, onSurface) }
        facts.technology?.let { MetricLine("Technology", it, onSurface) }
        MetricLine("Cycles", facts.cycles ?: "--", onSurface)
        MetricLine(
            "Temperature",
            facts.battery?.temperatureC.finiteOrNull()?.let { String.format("%.1f°C", it) } ?: "--",
            warn
        )
        MetricLine("Voltage", facts.battery?.voltageMilliVolts?.let { "$it mV" } ?: "--", onSurface)
        MetricLine(
            "Power",
            facts.battery?.watts.finiteOrNull()?.let { String.format("%.2f W", it) } ?: "--",
            onSurface
        )

        divider()
        Text("Thermal", style = MaterialTheme.typography.titleSmall, color = onSurface)
        MetricLine(
            "Phone temperature",
            system.deviceTemperatureC().finiteOrNull()?.let { String.format("%.1f°C", it) } ?: "--",
            warn
        )
        MetricLine(
            "Peak temperature",
            system.tempPeakC.finiteOrNull()?.let { String.format("%.1f°C", it) } ?: "--",
            warn
        )
        MetricLine(
            "Cooled from peak",
            system.tempCooledC.finiteOrNull()?.let { String.format("%.1f°C", it) } ?: "--",
            if ((system.tempCooledC.finiteOrNull() ?: 0f) >= 0.1f) good else muted
        )
        MetricLine(
            "State",
            system.thermalStatus?.let { thermalStatusLabel(it) } ?: "--",
            if ((system.thermalStatus ?: 0) > 0) warn else muted
        )
        MetricLine(
            "Hottest sensor",
            system.thermalMaxC.finiteOrNull()?.let { String.format("%.1f°C", it) } ?: "--",
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
                        "No privileged shell is connected. Start Shizuku or set up the local ADB bridge."
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
