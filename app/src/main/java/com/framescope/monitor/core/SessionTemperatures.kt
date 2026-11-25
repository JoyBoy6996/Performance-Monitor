package com.framescope.monitor.core

/**
 * The temperature that the chart of the run draws.
 *
 * A run of the newer app stores the temperature of the chip twice - once as
 * the CPU temperature and once as the SoC temperature - and a run of an older
 * app stores it under the old name. A run that stored none of them falls back
 * to the battery, which is the closest thermometer it has.
 *
 * This is the curve of the chart. The warning about a hot chip is a different
 * thing and never uses the battery: a battery is not a chip.
 */
fun SessionRecord.chartTemperatures(): List<Float> =
    socTempSeries.takeIf { it.isNotEmpty() }
        ?: cpuTempSeries.takeIf { it.isNotEmpty() }
        ?: tempSeries.takeIf { it.isNotEmpty() }
        ?: batteryTempSeries

/**
 * The temperature of the chip, second by second.
 *
 * The battery is never part of it. A warning about a hot chip that was
 * triggered by the temperature of the battery is a false warning.
 */
fun SessionRecord.chipTemperatures(): List<Float> =
    cpuTempSeries.takeIf { it.isNotEmpty() }
        ?: socTempSeries.takeIf { it.isNotEmpty() }
        ?: tempSeries
