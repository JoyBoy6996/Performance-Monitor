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

/**
 * The device metrics that need a privileged shell.
 *
 * These are the readings a normal app cannot get: the CPU frequency node, the GPU clock
 * and the detailed thermal sensors. They are only available while Shizuku is connected,
 * and each one reports its own state so that a command which fails does not take the
 * others down with it.
 */
class SystemSampler(private val hub: ShellHub) {

    private var lastCpu: LongArray? = null
    private var lastCpuCores: Map<Int, LongArray>? = null
    private var lastGpuBusy: Pair<Long, Long>? = null
    private var lastGroupNames: List<String> = emptyList()

    /**
     * Reads the privileged metrics.
     *
     * The result always describes every metric, including the ones that could not be
     * read. It never returns null: the caller merges this over whatever the native
     * collectors produced and keeps the native values for everything that is missing
     * here.
     */
    suspend fun sample(): SystemMetrics {
        val now = android.os.SystemClock.elapsedRealtimeNanos()
        val availability = mutableMapOf<SystemMetric, MetricAvailability>()
        val sources = mutableMapOf<SystemMetric, String>()

        val script = ShellCommands.batch(
            ShellCommands.cpuStat(),
            ShellCommands.gpuInfo(),
            ShellCommands.cpuFreq(),
            ShellCommands.thermal(),
            ShellCommands.cpuCoreDump(),
            ShellCommands.cpuCoreFreq(),
            ShellCommands.gpuBusy()
        )
        val result = hub.execOrNull(script)

        val parts = if (result != null) ShellCommands.split(result.out) else emptyList()
        fun part(index: Int): String = parts.getOrElse(index) { "" }

        val cpu = cpuPercent(part(0))
        val gpu = part(1)
        val cpuFreq = Parsers.parseCpuFreq(part(2))
        val thermals = Parsers.parseThermals(part(3))
        val cpuGroups = cpuGroups(part(4), part(5))
        val gpuPercent = gpuPercent(part(6))

        val gpuName = Parsers.parseGpuName(gpu)
        val gpuFreq = Parsers.parseGpuFreq(gpu)
        // Some devices expose placeholder sensors at 99.9 or impossible hotspots.
        // Neither is the temperature of the phone the user is holding, so reject
        // them before taking the maximum.
        val maxThermal = thermals.values.mapNotNull { it.plausibleTempC(MAX_HOTSPOT_TEMP_C) }.maxOrNull()
        val cpuTemp = thermals.entries
            .filter { (name, value) ->
                name.contains("cpu", true) || name.contains("soc", true) ||
                    name.contains("cluster", true)
            }
            .mapNotNull { it.value.plausibleTempC(MAX_HOTSPOT_TEMP_C) }
            .maxOrNull()
        val thermalStatus = Parsers.parseThermalStatus(part(3))

        record(availability, sources, SystemMetric.CPU_PERCENT, cpu != null, "CPU_PERCENT", "privileged /proc/stat")
        record(availability, sources, SystemMetric.CPU_FREQ_MHZ, cpuFreq != null, "CPU_FREQ_MHZ", "privileged CPU frequency node")
        record(availability, sources, SystemMetric.GPU_NAME, gpuName != null, "GPU_NAME", "privileged SurfaceFlinger")
        record(availability, sources, SystemMetric.GPU_FREQ_MHZ, gpuFreq != null, "GPU_FREQ_MHZ", "privileged GPU frequency node")
        record(availability, sources, SystemMetric.THERMAL_MAX_C, maxThermal != null, "THERMAL_MAX_C", "privileged thermalservice")
        record(availability, sources, SystemMetric.THERMAL_STATUS, thermalStatus != null, "THERMAL_STATUS", "thermalservice status")

        return SystemMetrics(
            cpuPercent = cpu,
            cpuFreqMHz = cpuFreq?.let { it / 1000 },
            gpuName = gpuName,
            gpuFreqMHz = gpuFreq?.let { it / 1000 },
            gpuPercent = gpuPercent,
            cpuGroups = cpuGroups,
            thermalMaxC = maxThermal,
            cpuTempC = cpuTemp,
            thermalStatus = thermalStatus,
            updatedAt = System.currentTimeMillis(),
            availability = availability,
            sources = sources,
            measuredAtElapsedNanos = now
        )
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

    /** GPU load from busy/total ticks; a device without counters gets null. */
    private fun gpuPercent(dump: String): Float? {
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

    /**
     * The CPU load between two readings of `/proc/stat`.
     *
     * A single reading is not enough: the counters only say how much time was used in
     * total, so the load is the difference between two of them.
     */
    private fun cpuPercent(cpuDump: String): Float? {
        val current = Parsers.parseCpuStat(cpuDump) ?: return null
        val previous = lastCpu
        lastCpu = current
        if (previous == null) return null
        val totalDelta = current[0] - previous[0]
        val idleDelta = current[1] - previous[1]
        if (totalDelta <= 0L) return null
        val usage = (1.0 - idleDelta.toDouble() / totalDelta.toDouble()) * 100.0
        return usage.toFloat().coerceIn(0f, 100f)
    }
}
