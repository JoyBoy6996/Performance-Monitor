package com.framescope.monitor.metrics

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.view.Display
import com.framescope.monitor.core.SystemMetrics
import com.framescope.monitor.shell.Parsers
import com.framescope.monitor.shell.ShellCommands
import com.framescope.monitor.shell.ShellHub
import kotlin.math.abs

/** Collects device metrics that change slowly (CPU, RAM, battery, thermals, GPU). */
class SystemSampler(
    private val hub: ShellHub,
    var context: Context? = null
) {

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
        val result = hub.execOrNull(script)
        val out = result?.out.orEmpty()
        val parts = if (out.isNotBlank()) ShellCommands.split(out) else emptyList()
        fun part(index: Int): String = parts.getOrElse(index) { "" }

        val cpu = cpuPercent(part(0))
        val (total, available) = Parsers.parseMemInfo(part(1))
        val battery = Parsers.parseBattery(part(2))
        val thermals = Parsers.parseThermals(part(3))
        val gpu = part(4)
        val cpuFreq = Parsers.parseCpuFreq(part(5))
        var refresh = Parsers.parseRefreshRate(part(6))

        var level = battery["level"]?.toIntOrNull()
        var batteryTemp = battery["temperature"]?.toFloatOrNull()?.let { it / 10f }
        var current = battery["current_now"]?.toLongOrNull()?.let { abs(it) }
        var voltage = battery["voltage"]?.toLongOrNull()

        // Native Android BatteryManager & Display fallback (works without root or Shizuku)
        val ctx = context
        if (ctx != null) {
            if (level == null || batteryTemp == null || current == null || voltage == null) {
                val sticky = runCatching {
                    ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                }.getOrNull()
                if (sticky != null) {
                    if (level == null) {
                        val rawLvl = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                        val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                        if (rawLvl >= 0 && scale > 0) level = (rawLvl * 100) / scale
                    }
                    if (batteryTemp == null) {
                        val rawTemp = sticky.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
                        if (rawTemp > 0) batteryTemp = rawTemp / 10f
                    }
                    if (voltage == null) {
                        val rawVolt = sticky.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
                        if (rawVolt > 0) voltage = rawVolt.toLong()
                    }
                }
                if (current == null || current == 0L) {
                    val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                    val rawCur = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                    if (rawCur != null && rawCur != 0 && rawCur != Int.MIN_VALUE) {
                        current = abs(rawCur.toLong())
                    }
                }
            }
            if (refresh == null) {
                val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                @Suppress("DEPRECATION")
                refresh = dm?.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate
            }
        }

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
        val gpuName = Parsers.parseGpuName(gpu)
        val cpuMhz = cpuFreq?.let { it / 1000 }
        val validPower = power?.takeIf { it > 0.01f && it < 150f }

        val hasAnyMetric = listOf<Any?>(
            cpu, cpuMhz, gpuName, gpuMhz, level, batteryTemp,
            validPower, usedGb, totalGb, ramPercent, maxThermal, refresh
        ).any { it != null }
        if (!hasAnyMetric) return null

        return SystemMetrics(
            cpuPercent = cpu,
            cpuFreqMHz = cpuMhz,
            gpuName = gpuName,
            gpuFreqMHz = gpuMhz,
            batteryLevel = level,
            batteryTempC = batteryTemp,
            powerWatts = validPower,
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
