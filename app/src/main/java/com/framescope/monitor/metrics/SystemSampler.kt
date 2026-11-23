package com.framescope.monitor.metrics

import com.framescope.monitor.core.CpuGroupMetric
import com.framescope.monitor.core.MAX_HOTSPOT_TEMP_C
import com.framescope.monitor.core.MetricAvailability
import com.framescope.monitor.core.SystemMetric
import com.framescope.monitor.core.SystemMetrics
import com.framescope.monitor.core.plausibleTempC
import com.framescope.monitor.shell.Parsers
import com.framescope.monitor.shell.ShellCommands
import com.framescope.monitor.shell.ShellHub
import java.io.File
import java.util.Locale

/** A chip sensor may legitimately read above the 85 C hotspot warning threshold. */
private const val MAX_CHIP_TEMP_C = 110f

/**
 * The device metrics that need a privileged shell.
 *
 * Every procfs/sysfs read goes through [ShellHub.execTelemetry], which uses the
 * SAME uid=2000 Shizuku UserService as the SurfaceFlinger frame probe. The app's
 * own shell cannot read these nodes on Android 16 and would answer with zeros.
 */
class SystemSampler(private val hub: ShellHub) {

    private var lastCpu: LongArray? = null
    /** Kept separate from the batch reader so the two deltas never interfere. */
    private var lastFastCpu: LongArray? = null
    private var lastDirectCpu: LongArray? = null
    private var directCpuStalls = 0
    private var lastFastCpuSource = ""
    private var lastCpuCores: Map<Int, LongArray>? = null
    private var lastGpuBusy: Pair<Long, Long>? = null
    private var lastGroupNames: List<String> = emptyList()
    private val sceneCollectors = SceneCollectors()

    /** Forgets the per-core and per-thread deltas when a new run begins. */
    fun resetDeltas() {
        sceneCollectors.reset()
        lastFastCpu = null
        lastDirectCpu = null
        directCpuStalls = 0
        lastFastCpuSource = ""
        lastGpuBusy = null
    }

    /**
     * Aggregate CPU load on its own fast clock. While Shizuku is connected the
     * counter is read by the privileged shell only - never by the app process.
     */
    suspend fun sampleCpu(): Float? {
        val privileged = hub.hasPrivilegedShell()
        val direct = if (privileged) "" else runCatching {
            File("/proc/stat").bufferedReader().use { it.readText() }
        }.getOrDefault("")
        val directParsed = Parsers.parseCpuStat(direct)
        if (directParsed != null) {
            directCpuStalls = if (
                lastDirectCpu != null && directParsed.contentEquals(lastDirectCpu!!)
            ) directCpuStalls + 1 else 0
            lastDirectCpu = directParsed
        }

        val useDirect = !privileged && directParsed != null && directCpuStalls < 3
        val source = if (useDirect) "direct" else "shell"
        val dump = if (useDirect) {
            direct
        } else {
            runCatching { hub.execTelemetry(ShellCommands.cpuStat(), 1_500L) }
                .getOrNull()?.out.orEmpty()
        }
        val current = Parsers.parseCpuStat(dump) ?: return null
        // A delta between two different providers is not a measurement. Drop the
        // baseline on a source switch, then start again from the next real pair.
        if (lastFastCpuSource != source) lastFastCpu = null
        lastFastCpuSource = source
        val value = cpuPercentBetween(lastFastCpu, current)
        lastFastCpu = current
        return value
    }

    /**
     * One batched privileged read per tick. Every hardware value in a session comes
     * from this single answer, so the report cannot mix two different shell identities.
     */
    suspend fun sample(packageName: String = "", timeoutMs: Long = 8_000L): SystemMetrics {
        val now = android.os.SystemClock.elapsedRealtimeNanos()
        val availability = mutableMapOf<SystemMetric, MetricAvailability>()
        val sources = mutableMapOf<SystemMetric, String>()

        val script = ShellCommands.batch(
            ShellCommands.cpuStat(),          // 0
            ShellCommands.gpuInfo(),          // 1
            ShellCommands.cpuFreq(),          // 2
            ShellCommands.thermal(),          // 3
            ShellCommands.cpuCoreDump(),      // 4
            ShellCommands.cpuCoreFreq(),      // 5
            ShellCommands.gpuBusy(),          // 6
            ShellCommands.ddrFreq(),          // 7
            ShellCommands.threadStat(packageName), // 8
            ShellCommands.clockTicks(),       // 9
            ShellCommands.telemetryInit(),    // 10
            ShellCommands.thermalSysfs()      // 11
        )
        val result = runCatching { hub.execTelemetry(script, timeoutMs) }.getOrNull()

        val parts = if (result != null) ShellCommands.split(result.out) else emptyList()
        fun part(index: Int): String = parts.getOrElse(index) { "" }

        val cpu = cpuPercent(part(0))
        val gpu = part(1)
        val cpuFreq = Parsers.parseCpuFreq(part(2))
        val thermals = Parsers.parseThermals(part(3))
        val cpuGroups = cpuGroups(part(4), part(5))
        val gpuPercent = gpuPercent(part(6))
        val scene = sceneCollectors.parse(
            coreDump = part(4),
            coreFreqDump = part(5),
            ddrDump = part(7),
            threadDump = part(8),
            clockDump = part(9),
            nowWallMs = System.currentTimeMillis()
        )

        val gpuName = Parsers.parseGpuName(gpu)
        val gpuFreq = Parsers.parseGpuFreq(gpu)

        // Named kernel sensors are the real chip temperature. `dumpsys thermalservice`
        // stays as a fallback for devices that do not expose named sysfs zones.
        val zones = Parsers.parseThermalZones(part(11))
        val chipZones = zones.filter(::isChipZone)
        val chipTemps = chipZones.mapNotNull(::zoneCelsius)
        val cpuTemp = chipTemps.maxOrNull() ?: thermals.entries
            .filter { (name, _) ->
                name.contains("cpu", true) || name.contains("soc", true) ||
                    name.contains("cluster", true)
            }
            .mapNotNull { it.value.plausibleTempC(MAX_CHIP_TEMP_C) }
            .maxOrNull()
        // The matched chip zone is used for BOTH cpuTempC and socTempC. A generic
        // "all sensors" maximum can be a battery skin reading and must not win.
        val maxThermal = chipTemps.maxOrNull() ?: (zones.mapNotNull(::zoneCelsius) +
            thermals.values.mapNotNull { it.plausibleTempC(MAX_HOTSPOT_TEMP_C) })
            .maxOrNull()
        val thermalStatus = Parsers.parseThermalStatus(part(3))
        val identity = Parsers.parseDeviceIdentity(part(10))
        val detail = telemetryDetail(part(10), identity.first, chipZones, cpuGroups)
        // Keep the unparsed privileged answer too: Shell Logs must show the actual
        // UID/PLATFORM/GAME-MODE/CPUMAX/TZLINE lines, not a hand-written summary.
        val rawTelemetry = listOf(part(10), part(11))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")

        record(availability, sources, SystemMetric.CPU_PERCENT, cpu != null, "CPU_PERCENT", "privileged /proc/stat")
        record(availability, sources, SystemMetric.CPU_FREQ_MHZ, cpuFreq != null, "CPU_FREQ_MHZ", "privileged CPU frequency node")
        record(availability, sources, SystemMetric.GPU_NAME, gpuName != null, "GPU_NAME", "privileged SurfaceFlinger")
        record(availability, sources, SystemMetric.GPU_FREQ_MHZ, gpuFreq != null, "GPU_FREQ_MHZ", "privileged GPU frequency node")
        record(availability, sources, SystemMetric.THERMAL_MAX_C, maxThermal != null, "THERMAL_MAX_C", "named thermal zones")
        record(availability, sources, SystemMetric.CPU_TEMP_C, cpuTemp != null, "CPU_TEMP_C", "named CPU/SoC thermal zone")
        record(
            availability, sources, SystemMetric.GPU_PERCENT, gpuPercent != null,
            "GPU_PERCENT", "GPU busy counters (KGSL/Mali/devfreq)"
        )
        record(availability, sources, SystemMetric.CPU_CORES, scene.cpuCoreLoadPct.any { it != null }, "CPU_CORES", "per-core /proc/stat")
        record(availability, sources, SystemMetric.DDR_FREQ_MHZ, scene.ddrFreqMHz != null, "DDR_FREQ_MHZ", "memory devfreq")
        record(availability, sources, SystemMetric.THREAD_LOAD, scene.threadLoads.isNotEmpty(), "THREAD_LOAD", "per-thread /proc/<pid>/task")
        record(availability, sources, SystemMetric.THERMAL_STATUS, thermalStatus != null, "THERMAL_STATUS", "thermalservice status")

        return SystemMetrics(
            cpuPercent = cpu,
            cpuFreqMHz = cpuFreq?.let { it / 1000 },
            gpuName = gpuName,
            gpuFreqMHz = gpuFreq?.let { it / 1000 },
            gpuPercent = gpuPercent,
            cpuGroups = cpuGroups,
            cpuCoreLoadPct = scene.cpuCoreLoadPct,
            cpuCoreFreqMHz = scene.cpuCoreFreqMHz,
            ddrFreqMHz = scene.ddrFreqMHz,
            threadLoads = scene.threadLoads,
            thermalMaxC = maxThermal,
            cpuTempC = cpuTemp,
            thermalStatus = thermalStatus,
            platform = identity.first,
            performanceProfile = identity.second,
            telemetryDetail = detail,
            telemetryRaw = rawTelemetry,
            updatedAt = System.currentTimeMillis(),
            availability = availability,
            sources = sources,
            measuredAtElapsedNanos = now
        )
    }

    /** Only a CPU/GPU/SoC sensor counts as chip temperature; battery sensors never do. */
    private fun isChipZone(zone: Parsers.ThermalZoneReading): Boolean {
        val name = zone.type.lowercase(Locale.US)
        val excluded = listOf("battery", "bms", "skin", "usb", "qi", "charger", "cam", "pa")
        if (excluded.any { name.contains(it) }) return false
        val included = listOf("cpu", "soc", "apss", "tsens", "gold", "prime", "cluster")
        return included.any { name.contains(it) }
    }

    /** Kernel values are millidegrees on most nodes, degrees on a few. */
    private fun zoneCelsius(zone: Parsers.ThermalZoneReading): Float? {
        val raw = zone.raw ?: return null
        val celsius = if (raw > 1_000f) raw / 1_000f else raw
        return celsius.plausibleTempC(MAX_CHIP_TEMP_C)
    }

    /**
     * The exact identity line stored at the START of every recorded session and
     * repeated in its Shell Logs card. Values come from the same batched Shizuku
     * answer as the telemetry, never from Build.* guesses.
     */
    private fun telemetryDetail(
        identityDump: String,
        platform: String?,
        chipZones: List<Parsers.ThermalZoneReading>,
        groups: List<CpuGroupMetric>
    ): String {
        val uid = Parsers.parseShellUid(identityDump) ?: "--"
        val board = platform ?: "--"
        // Every matched CPU/SoC sensor is listed, never a battery or skin zone.
        val zones = chipZones.map { it.path }.distinct().joinToString(",")
            .ifBlank { "--" }
        val clusters = if (groups.isEmpty()) {
            "--"
        } else {
            groups.joinToString(", ") { group ->
                "${group.name}:${group.freqMHz?.let { "$it" } ?: "--"}MHz"
            }
        }
        return "shell_uid=$uid | platform=$board | thermal_zone=$zones | clusters=$clusters"
    }

    /**
     * Real CPU clusters: cores are grouped by the maximum clock the kernel reports.
     * A device that does not publish those nodes gets one line per core instead.
     */
    private fun cpuGroups(coreDump: String, coreFreqDump: String): List<CpuGroupMetric> {
        val current = Parsers.parsePerCoreCpuStat(coreDump)
        if (current.isEmpty()) return emptyList()
        val previous = lastCpuCores
        lastCpuCores = current

        val maxFreqs = Parsers.parseCpuMaxFreqs(coreDump)
        val nowFreqs = Parsers.parseCpuFreqs(coreFreqDump)
        val cores = current.keys.sorted()
        val grouped = if (maxFreqs.keys.containsAll(cores)) {
            cores.groupBy { maxFreqs[it] ?: 0 }
                .values.sortedByDescending { maxFreqs[it.first()] ?: 0 }
        } else {
            cores.map { listOf(it) }
        }

        // Keep the same order on every poll so a chart line never jumps to a
        // different colour when the shell returns the nodes in another order.
        val currentNames = grouped.map(::groupLabel)
        val ordered = if (lastGroupNames.isNotEmpty() &&
            lastGroupNames.toSet() == currentNames.toSet()
        ) {
            lastGroupNames.mapNotNull { name ->
                grouped.firstOrNull { groupLabel(it) == name }
            }
        } else {
            grouped
        }
        lastGroupNames = ordered.map(::groupLabel)

        return ordered.map { group ->
            var totalDelta = 0L
            var idleDelta = 0L
            group.forEach { core ->
                val now = current[core] ?: return@forEach
                val before = previous?.get(core) ?: return@forEach
                totalDelta += now[0] - before[0]
                idleDelta += now[1] - before[1]
            }
            val percent = if (totalDelta > 0L) {
                ((1.0 - idleDelta.toDouble() / totalDelta.toDouble()) * 100.0)
                    .toFloat().coerceIn(0f, 100f)
            } else null
            val freqKhz = group.mapNotNull { nowFreqs[it] }.filter { it > 0 }
            val freqMHz = if (freqKhz.isEmpty()) null else (freqKhz.average() / 1000).toInt()
            CpuGroupMetric(groupLabel(group), percent, freqMHz)
        }
    }

    private fun groupLabel(cores: List<Int>): String = if (cores.size == 1) {
        "CPU${cores.first()}"
    } else {
        "CPU ${cores.first()}~${cores.last()}"
    }

    /**
     * GPU load from a real counter. Mali-style devices publish the percentage
     * directly; others publish busy/total ticks that need a delta between polls.
     * A device with neither gets null, never a fake zero.
     */
    private fun gpuPercent(dump: String): Float? {
        Parsers.parseGpuPercentDirect(dump)?.let { percent ->
            lastGpuBusy = null
            return percent.coerceIn(0f, 100f)
        }
        val now = Parsers.parseGpuBusy(dump) ?: run {
            lastGpuBusy = null
            return null
        }
        val before = lastGpuBusy
        lastGpuBusy = now
        if (before == null || now.second <= before.second) return null
        val busyDelta = now.first - before.first
        if (busyDelta < 0L) return null
        return (busyDelta * 100f / (now.second - before.second).toFloat()).coerceIn(0f, 100f)
    }

    /**
     * Notes whether a metric could be read.
     *
     * A metric that is missing because the shell could not be reached needs Shizuku; one
     * that is missing because the device does not expose it does not.
     */
    private fun record(
        availability: MutableMap<SystemMetric, MetricAvailability>,
        sources: MutableMap<SystemMetric, String>,
        metric: SystemMetric,
        present: Boolean,
        source: String,
        @Suppress("UNUSED_PARAMETER") sourceDetail: String
    ) {
        sources[metric] = source
        availability[metric] = if (present) {
            MetricAvailability.AVAILABLE
        } else {
            MetricAvailability.SHIZUKU_REQUIRED
        }
    }

    /** The CPU load between two readings of `/proc/stat`. */
    private fun cpuPercent(cpuDump: String): Float? {
        val current = Parsers.parseCpuStat(cpuDump) ?: return null
        val previous = lastCpu
        lastCpu = current
        return cpuPercentBetween(previous, current)
    }

    /** Difference of two cumulative `/proc/stat` snapshots into a busy percentage. */
    private fun cpuPercentBetween(previous: LongArray?, current: LongArray): Float? {
        if (previous == null) return null
        val totalDelta = current[0] - previous[0]
        val idleDelta = current[1] - previous[1]
        if (totalDelta <= 0L) return null
        val usage = (1.0 - idleDelta.toDouble() / totalDelta.toDouble()) * 100.0
        return usage.toFloat().coerceIn(0f, 100f)
    }
}
