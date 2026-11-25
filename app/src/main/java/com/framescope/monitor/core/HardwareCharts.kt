package com.framescope.monitor.core

/**
 * Which charts of the hardware a run really measured.
 *
 * The app and the report use one answer for this, so a run that has no CPU
 * samples shows the same thing on the screen and on the paper. A chart
 * without readings is an empty box, and an empty box is not a chart.
 */
data class HardwareCharts(
    /** The run measured the load of the processor. */
    val cpuUsage: Boolean,
    /** The run measured the clock of the processor. */
    val cpuFrequency: Boolean,
    /** The run measured the temperature of the chip. */
    val cpuTemperature: Boolean
) {
    /** True when at least one of the charts has readings. */
    val any: Boolean get() = cpuUsage || cpuFrequency || cpuTemperature

    /** The charts that have no readings, by name. */
    val missing: List<String> get() = listOfNotNull(
        if (!cpuUsage) "CPU Usage" else null,
        if (!cpuFrequency) "CPU Frequency" else null,
        if (!cpuTemperature) "CPU Temperature" else null
    )
}

/**
 * The charts of the hardware a run can show.
 *
 * A legacy run that was recorded before the app kept the counters has none
 * of them, and the charts are left out instead of being drawn empty.
 */
fun SessionRecord.hardwareCharts(): HardwareCharts = HardwareCharts(
    cpuUsage = cpuSeries.count { it.finiteOrNull() != null } >= 2 ||
        cpuGroupSeries.any { row -> row.any { it?.finiteOrNull() != null } },
    cpuFrequency = cpuFreqSeries.count { it.finiteOrNull() != null } >= 2 ||
        cpuGroupFreqSeries.any { row -> row.any { it?.finiteOrNull() != null } },
    cpuTemperature = chartTemperatures().count { it.finiteOrNull() != null } >= 2
)
