package com.framescope.monitor.core

/**
 * The temperature that the chart of the run draws.
 *
 * A run of the newer app stores the temperature of the chip twice - once as
 * the CPU temperature and once as the SoC temperature - and a run of an older
 * app stores it under the old name. A run that stored none of them falls back
 * to the battery, which is the closest thermometer it has.
 *
 * The list has one value for every second of the run, on the same seconds as
 * the frame rate. A second without a reading is a gap and not a drop to zero:
 * the line of the temperature has to sit above the line of the frame rate at
 * the same second, or the chart lies about the moment the chip was warm.
 *
 * This is the curve of the chart. The warning about a hot chip is a different
 * thing and never uses the battery: a battery is not a chip.
 */
fun SessionRecord.chartTemperatures(): List<Float> {
    val soc = socTempSeries
    val cpu = cpuTempSeries
    val legacy = tempSeries
    val battery = batteryTempSeries
    val size = maxOf(soc.size, cpu.size, legacy.size, battery.size)
    if (size == 0) return emptyList()
    return (0 until size).map { index ->
        soc.getOrNull(index)?.takeIf { it > 0f }
            ?: cpu.getOrNull(index)?.takeIf { it > 0f }
            ?: legacy.getOrNull(index)?.takeIf { it > 0f }
            ?: battery.getOrNull(index)?.takeIf { it > 0f }
            ?: Float.NaN
    }
}

/**
 * The temperature of the chip, second by second.
 *
 * The battery is never part of it. A warning about a hot chip that was
 * triggered by the temperature of the battery is a false warning.
 */
fun SessionRecord.chipTemperatures(): List<Float> {
    val cpu = cpuTempSeries.filter { it > 0f }
    val soc = socTempSeries.filter { it > 0f }
    val legacy = tempSeries.filter { it > 0f }
    return cpu.ifEmpty { soc }.ifEmpty { legacy }
}

/**
 * The temperature of the chip on every second of the run.
 *
 * The battery is never part of it: the card of the CPU temperature shows the
 * chip, and a battery is not a chip. A second without a reading is a gap in
 * the line and not a drop to zero.
 */
fun SessionRecord.chipTemperatureSeries(): List<Float> {
    val cpu = cpuTempSeries
    val soc = socTempSeries
    val legacy = tempSeries
    val size = maxOf(cpu.size, soc.size, legacy.size)
    if (size == 0) return emptyList()
    return (0 until size).map { index ->
        soc.getOrNull(index)?.takeIf { it > 0f }
            ?: cpu.getOrNull(index)?.takeIf { it > 0f }
            ?: legacy.getOrNull(index)?.takeIf { it > 0f }
            ?: Float.NaN
    }
}
