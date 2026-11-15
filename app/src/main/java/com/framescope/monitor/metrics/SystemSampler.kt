package com.framescope.monitor.metrics

import com.framescope.monitor.core.MetricAvailability
import com.framescope.monitor.core.SystemMetric
import com.framescope.monitor.core.SystemMetrics
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
            ShellCommands.thermal()
        )
        val result = hub.execOrNull(script)

        val parts = if (result != null) ShellCommands.split(result.out) else emptyList()
        fun part(index: Int): String = parts.getOrElse(index) { "" }

        val cpu = cpuPercent(part(0))
        val gpu = part(1)
        val cpuFreq = Parsers.parseCpuFreq(part(2))
        val thermals = Parsers.parseThermals(part(3))

        val gpuName = Parsers.parseGpuName(gpu)
        val gpuFreq = Parsers.parseGpuFreq(gpu)
        val maxThermal = thermals.values.maxOrNull()

        record(availability, sources, SystemMetric.CPU_PERCENT, cpu != null, "CPU_PERCENT", "privileged /proc/stat")
        record(availability, sources, SystemMetric.CPU_FREQ_MHZ, cpuFreq != null, "CPU_FREQ_MHZ", "privileged CPU frequency node")
        record(availability, sources, SystemMetric.GPU_NAME, gpuName != null, "GPU_NAME", "privileged SurfaceFlinger")
        record(availability, sources, SystemMetric.GPU_FREQ_MHZ, gpuFreq != null, "GPU_FREQ_MHZ", "privileged GPU frequency node")
        record(availability, sources, SystemMetric.THERMAL_MAX_C, maxThermal != null, "THERMAL_MAX_C", "privileged thermalservice")

        return SystemMetrics(
            cpuPercent = cpu,
            cpuFreqMHz = cpuFreq?.let { it / 1000 },
            gpuName = gpuName,
            gpuFreqMHz = gpuFreq?.let { it / 1000 },
            thermalMaxC = maxThermal,
            updatedAt = System.currentTimeMillis(),
            availability = availability,
            sources = sources,
            measuredAtElapsedNanos = now
        )
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
