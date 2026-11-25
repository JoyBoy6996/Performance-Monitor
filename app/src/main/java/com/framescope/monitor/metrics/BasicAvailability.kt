package com.framescope.monitor.metrics

import com.framescope.monitor.core.SystemMetric

/**
 * The metrics that the app's own reading fills.
 *
 * The privileged shell answers most of the questions about the hardware. The
 * app reads what the system lets it read, and every question the shell left
 * open that the app could answer is answered.
 *
 * The metric is then available: a real number is on the screen, and telling
 * the user to install Shizuku for a number that is already there is wrong.
 */
fun basicFilledMetrics(sample: BasicCollector.Sample?): Set<SystemMetric> {
    if (sample == null) return emptySet()
    val filled = mutableSetOf<SystemMetric>()
    if (sample.cpuPercent != null) filled.add(SystemMetric.CPU_PERCENT)
    if (sample.cpuFreqMhz != null) filled.add(SystemMetric.CPU_FREQ_MHZ)
    if (sample.gpuClockMhz != null) filled.add(SystemMetric.GPU_FREQ_MHZ)
    if (sample.gpuPercent != null) filled.add(SystemMetric.GPU_PERCENT)
    if (sample.cpuTempC != null) {
        filled.add(SystemMetric.THERMAL_MAX_C)
        filled.add(SystemMetric.CPU_TEMP_C)
    }
    if (sample.perCoreFreqMhz.any { it != null }) filled.add(SystemMetric.CPU_CORES)
    return filled
}
