package com.framescope.monitor.metrics

import com.framescope.monitor.core.SystemMetrics
import com.framescope.monitor.shell.Parsers
import com.framescope.monitor.shell.ShellCommands
import com.framescope.monitor.shell.ShellHub
import kotlin.math.abs

/** Collects device metrics that change slowly (CPU, RAM, battery, thermals, GPU). */
class SystemSampler(private val hub: ShellHub) {

    private var lastCpu: LongArray? = null

    suspend fun sample(): SystemMetrics? {
        val script = ShellCommands.batch(
            ShellCommands.cpuStat(),
            ShellCommands.memInfo(),
            ShellCommands.battery(),
            ShellCommands.thermal(),
            ShellCommands.gpuInfo(),
            ShellCommands.cpuFreq(),
            ShellCommands.displayInfo()
        )
        val result = hub.execOrNull(script) ?: return null
        val out = result.out
        if (out.isBlank()) return null
        val parts = ShellCommands.split(out)
        if (parts.isEmpty()) return null
        fun part(index: Int): String = parts.getOrElse(index) { "" }

        val cpu = cpuPercent(part(0))
        val (total, available) = Parsers.parseMemInfo(part(1))
        val battery = Parsers.parseBattery(part(2))
        val thermals = Parsers.parseThermals(part(3))
        val gpu = part(4)
        val cpuFreq = Parsers.parseCpuFreq(part(5))
        val refresh = Parsers.parseRefreshRate(part(6))

        val level = battery["level"]?.toIntOrNull()
        val batteryTemp = battery["temperature"]?.toFloatOrNull()?.let { it / 10f }
        val current = battery["current_now"]?.toLongOrNull()?.let { abs(it) }
        val voltage = battery["voltage"]?.toLongOrNull()
        val power = if (current != null && voltage != null && current > 0L && voltage > 0L) {
            val amps = if (current > 20_000L) current / 1_000_000.0 else current / 1_000.0
            val volts = if (voltage > 100L) voltage / 1_000.0 else voltage.toDouble()
            (amps * volts).toFloat()
        } else null

        val usedGb = if (total > 0) (total - available) / 1_073_741_824f else null
        val totalGb = if (total > 0) total / 1_073_741_824f else null
        val ramPercent = if (total > 0) ((total - available) * 100 / total).toInt() else null
        val maxThermal = thermals.values.filter { it in 10f..120f }.maxOrNull() ?: batteryTemp

        val rawGpuFreq = Parsers.parseGpuFreq(gpu)
        val gpuMhz = rawGpuFreq?.let { if (it > 10_000) it / 1_000_000 else it / 1000 }

        return SystemMetrics(
            cpuPercent = cpu,
            cpuFreqMHz = cpuFreq?.let { it / 1000 },
            gpuName = Parsers.parseGpuName(gpu),
            gpuFreqMHz = gpuMhz,
            batteryLevel = level,
            batteryTempC = batteryTemp,
            powerWatts = power?.takeIf { it > 0.01f && it < 150f },
            ramUsedGb = usedGb,
            ramTotalGb = totalGb,
            ramPercent = ramPercent,
            thermalMaxC = maxThermal,
            refreshHz = refresh,
            updatedAt = System.currentTimeMillis()
        )
    }

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
