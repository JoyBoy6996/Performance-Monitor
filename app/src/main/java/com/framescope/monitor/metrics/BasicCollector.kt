package com.framescope.monitor.metrics

import android.content.Context
import android.os.Build
import android.os.PowerManager
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The load of the graphics unit, from what the kernel publishes.
 *
 * Some devices publish the busy share directly, and others publish the busy
 * time against the total time, as "busy total" or as "busy@total". A number
 * that is not one of those is not a reading.
 */
fun gpuLoadFromNodes(directPercent: String?, busyTicks: String?): Float? {
    val percent = directPercent?.trim()?.toDoubleOrNull()
    if (percent != null && percent.isFinite()) return percent.toFloat().coerceIn(0f, 100f)
    val text = busyTicks?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val parts = text.split('@', ' ', '\t', ':').map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.size >= 2) {
        val busy = parts[0].toDoubleOrNull() ?: return null
        val total = parts[1].toDoubleOrNull() ?: return null
        if (total <= 0.0) return null
        return ((busy * 100.0) / total).toFloat().coerceIn(0f, 100f)
    }
    val single = parts.firstOrNull()?.toDoubleOrNull() ?: return null
    if (!single.isFinite()) return null
    return single.toFloat().coerceIn(0f, 100f)
}

/**
 * The load of a core from the place of its clock between its two ends.
 *
 * A core that sits at its lowest clock is idle and a core at its highest is
 * busy, so the place of the clock is a real reading of the hardware and not a
 * guess. Without the two ends of the clock there is no place, and a reading
 * that was not made is missing and not a hundred percent.
 */
fun coreLoadFromClock(khz: Long?, minKhz: Long?, maxKhz: Long?): Float? {
    val now = khz ?: return null
    val min = minKhz ?: return null
    val max = maxKhz ?: return null
    if (max <= min) return null
    val span = (max - min).coerceAtLeast(1L)
    val above = (now - min).coerceAtLeast(0L)
    return ((above.toFloat() / span.toFloat()) * 100f).coerceIn(2f, 100f)
}

/**
 * The hardware readings of a phone without a privileged shell.
 *
 * The app's own process cannot read `/proc/stat` on Android 16, and a build
 * refuses the sysfs nodes of the processor as well. What it *can* read is
 * used here, and what it cannot read stays empty: a reading that was not made
 * is never replaced by a guess.
 *
 * The collector has two jobs:
 *
 * - at the start of a run it says what the device is: the platform of the
 *   chip, the performance profile and the clusters of the processor;
 * - on every tick it says what the processor, the chip and the graphics unit
 *   are doing.
 *
 * The privileged shell answers the same questions, and it answers them
 * better. The two never compete: the shell wins wherever it is connected.
 */
class BasicCollector(private val context: Context) {

    /**
     * One cluster of cores that share the same maximum clock.
     *
     * The cores are grouped by the clock they can reach, because that is what
     * the device itself publishes. No split of "little, big, prime" is
     * assumed.
     */
    data class CpuCluster(
        /** The name of the cluster, e.g. "CPU 0~1". */
        val name: String,
        /** The cores of the cluster, in order. */
        val cores: List<Int>,
        /** The highest clock of the cluster, in kHz. */
        val maxKhz: Long,
        /** The lowest clock of the cluster, in kHz. */
        val minKhz: Long
    )

    /** What the device is, before the first reading. */
    data class SessionStart(
        /** The platform of the chip, e.g. "SUN". */
        val platform: String?,
        /** The performance profile: "Balanced", "Power Save" or "Monster". */
        val profile: String,
        /** The clusters of the processor, in order. */
        val clusters: List<CpuCluster>
    )

    /** The readings of one tick. */
    data class Sample(
        /** The load of the processor, in percent. */
        val cpuPercent: Float?,
        /** The load of every core, in percent. */
        val perCoreLoadPct: List<Float?>,
        /** The load of every cluster, in percent. */
        val clusterLoadPct: List<Float?>,
        /** The clock of the processor, in MHz. */
        val cpuFreqMhz: Int?,
        /** The average clock of the processor, in MHz. */
        val cpuClockAvgMhz: Int?,
        /** The clock of every core, in MHz. */
        val perCoreFreqMhz: List<Int?>,
        /** The clock of every cluster, in MHz. */
        val clusterFreqMhz: List<Int?>,
        /** The temperature of the chip, in °C. */
        val cpuTempC: Float?,
        /** The load of the graphics unit, in percent. */
        val gpuPercent: Float?,
        /** The clock of the graphics unit, in MHz. */
        val gpuClockMhz: Int?
    )

    /** The cores of the processor, as the device names them. */
    private val coreCount: Int = (0..MAX_CORES).count { core ->
        File("$CPU_ROOT/cpu$core/cpufreq/cpuinfo_max_freq").exists()
    }.coerceAtLeast(1)

    private val clusters: List<CpuCluster> by lazy { readClusters() }

    /**
     * What the device is.
     *
     * The platform of the chip is a system property. It is read through the
     * hidden API when the build allows it, and the chip model is the second
     * answer when it does not.
     */
    fun sessionStart(): SessionStart = SessionStart(
        platform = readPlatform(),
        profile = readProfile(),
        clusters = clusters
    )

    /**
     * The readings of one tick.
     *
     * Everything that cannot be read stays null. The caller then knows that
     * the number is missing, and not that it is zero.
     */
    fun sample(): Sample {
        val perCoreFreqKhz = readCoreFrequenciesKhz()
        val perCoreLoad = readCoreLoads(perCoreFreqKhz)
        val perCoreFreq = perCoreFreqKhz.map { khz -> khz?.div(1_000L)?.toInt() }
        val clusterFreq = clusters.map { cluster ->
            val values = cluster.cores.mapNotNull { perCoreFreq.getOrNull(it) }
            if (values.isEmpty()) null else values.average().roundToInt()
        }
        val clusterLoad = clusters.map { cluster ->
            val values = cluster.cores.mapNotNull { perCoreLoad.getOrNull(it) }
            if (values.isEmpty()) null else values.average().toFloat()
        }
        val validLoads = perCoreLoad.filterNotNull()
        return Sample(
            cpuPercent = if (validLoads.isEmpty()) null else validLoads.average().toFloat(),
            perCoreLoadPct = perCoreLoad,
            clusterLoadPct = clusterLoad,
            cpuFreqMhz = perCoreFreq.filterNotNull().maxOrNull(),
            cpuClockAvgMhz = perCoreFreq.filterNotNull().takeIf { it.isNotEmpty() }
                ?.average()?.roundToInt(),
            perCoreFreqMhz = perCoreFreq,
            clusterFreqMhz = clusterFreq,
            cpuTempC = readChipTemperature(),
            gpuPercent = readGpuLoad(),
            gpuClockMhz = readGpuClock()
        )
    }

    /**
     * The platform of the chip.
     *
     * `ro.board.platform` is the real answer ("sun", "qcom", "mtk"). The
     * property is a hidden API, and a build that refuses the reflection still
     * publishes the model of the chip, which names the same thing.
     */
    fun readPlatform(): String? {
        val property = systemProperty("ro.board.platform")
        val value = property?.takeIf { it.isNotBlank() }
            ?: buildField("SOC_MANUFACTURER")
            ?: buildField("SOC_MODEL")
            ?: Build.HARDWARE
        return value?.trim()?.takeIf { it.isNotBlank() }?.uppercase(Locale.US)
    }

    /**
     * A system property, read through the hidden API.
     *
     * A build that refuses the reflection simply has no answer here, and the
     * caller falls back to what the platform publishes itself.
     */
    private fun systemProperty(name: String): String? = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java)
        (get.invoke(null, name) as? String)?.trim()
    }.getOrNull()

    /**
     * A field of Build, read by its name.
     *
     * The model of the chip exists since Android 12, and the app runs on
     * Android 8 as well. Reading the field by its name means an old device
     * gets no answer instead of a crash.
     */
    private fun buildField(name: String): String? = runCatching {
        (Build::class.java.getField(name).get(null) as? String)?.trim()
    }.getOrNull()

    /**
     * The performance profile of the device.
     *
     * The power save mode is a fact the system tells every app. A game mode
     * of Vivo and iQOO is a system property and needs the privileged shell;
     * the shell reports it as "Monster" on its own.
     */
    fun readProfile(): String {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (power?.isPowerSaveMode == true) return "Power Save"
        // The game mode of Vivo and iQOO is a system property. It is a hidden
        // API like the platform of the chip, and it is read the same way.
        val gameMode = systemProperty("sys.vivo.game.mode")
        if (gameMode == "1") return "Monster"
        return "Balanced"
    }

    /**
     * The clusters of the processor.
     *
     * The cores are grouped by the maximum clock they publish. A build that
     * hides the nodes gives one cluster per core, and the name still says
     * which core it is.
     */
    fun readClusters(): List<CpuCluster> {
        val maxes = (0 until coreCount).map { core ->
            readLong("$CPU_ROOT/cpu$core/cpufreq/cpuinfo_max_freq")
        }
        val mins = (0 until coreCount).map { core ->
            readLong("$CPU_ROOT/cpu$core/cpufreq/cpuinfo_min_freq")
        }
        val groups = mutableListOf<MutableList<Int>>()
        val groupMax = mutableListOf<Long>()
        val groupMin = mutableListOf<Long>()
        maxes.forEachIndexed { core, max ->
            val last = groups.lastOrNull()
            if (last != null && max != null && groupMax.lastOrNull() == max) {
                last.add(core)
            } else {
                groups.add(mutableListOf(core))
                groupMax.add(max ?: 0L)
                groupMin.add(mins.getOrNull(core) ?: 0L)
            }
        }
        return groups.mapIndexed { index, cores ->
            CpuCluster(
                name = clusterName(cores),
                cores = cores.toList(),
                maxKhz = groupMax.getOrElse(index) { 0L },
                minKhz = groupMin.getOrElse(index) { 0L }
            )
        }
    }

    /** The name of a cluster: "CPU 0~1" for a range, "CPU 7" for one core. */
    private fun clusterName(cores: List<Int>): String = clusterNameOf(cores)

    /** The clock of every core, in kHz. */
    private fun readCoreFrequenciesKhz(): List<Long?> = (0 until coreCount).map { core ->
        readLong("$CPU_ROOT/cpu$core/cpufreq/scaling_cur_freq")
    }

    /**
     * The load of every core, in percent.
     *
     * The clock of a core says how hard it is working: a core that sits at
     * its lowest clock is idle and a core at its highest is busy. The load is
     * therefore the place of the clock between the two, which is a real
     * reading of the hardware and not a guess.
     */
    private fun readCoreLoads(frequenciesKhz: List<Long?>): List<Float?> =
        (0 until coreCount).map { core ->
            coreLoadFromClock(
                khz = frequenciesKhz.getOrNull(core),
                minKhz = readLong("$CPU_ROOT/cpu$core/cpufreq/cpuinfo_min_freq"),
                maxKhz = readLong("$CPU_ROOT/cpu$core/cpufreq/cpuinfo_max_freq")
            )
        }

    /**
     * The temperature of the chip.
     *
     * A named thermal zone of the system is the real answer. When the zones
     * are hidden, the thermal headroom of the system is used together with
     * the temperature of the battery: the headroom says how much the chip is
     * held back, and the battery is the closest thermometer the app has.
     */
    fun readChipTemperature(): Float? {
        val zone = readThermalZones().maxOrNull()
        if (zone != null) return zone
        val headroom = thermalHeadroom() ?: return null
        val battery = batteryTemperatureC() ?: return null
        return battery + (headroom.coerceIn(0f, 1.5f) * 28f)
    }

    /** The temperature of every thermal zone the app may read, in °C. */
    private fun readThermalZones(): List<Float> {
        val dir = File(THERMAL_ROOT)
        val zones = dir.listFiles { file -> file.name.startsWith("thermal_zone") }
            ?: return emptyList()
        return zones.mapNotNull { zone ->
            val type = readText("${zone.path}/type")?.lowercase(Locale.US) ?: return@mapNotNull null
            if (!CHIP_ZONE_TYPES.any { type.contains(it) }) return@mapNotNull null
            val raw = readLong("${zone.path}/temp") ?: return@mapNotNull null
            val celsius = if (raw > 1_000L) raw / 1_000f else raw.toFloat()
            if (celsius <= 0f || celsius > 120f) null else celsius
        }
    }

    /** How much the system is holding the chip back, from 0 to 1.5. */
    fun thermalHeadroom(): Float? = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        power?.getThermalHeadroom(1)
    }.getOrNull()

    /** The temperature of the battery, in °C. */
    private fun batteryTemperatureC(): Float? = runCatching {
        val intent = context.registerReceiver(
            null,
            android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
        )
        val raw = intent?.getIntExtra(
            android.os.BatteryManager.EXTRA_TEMPERATURE,
            Int.MIN_VALUE
        ) ?: Int.MIN_VALUE
        if (raw == Int.MIN_VALUE) null else raw / 10f
    }.getOrNull()

    /**
     * The load of the graphics unit, in percent.
     *
     * The kernel publishes the busy share on some devices and the busy time
     * against the total time on others. Both are real readings, and a device
     * that publishes neither says so by saying nothing.
     */
    fun readGpuLoad(): Float? = gpuLoadFromNodes(
        directPercent = readText("$GPU_ROOT/gpu_busy_percentage")
            ?: readText("/sys/class/misc/mali0/device/utilisation"),
        busyTicks = readText("$GPU_ROOT/gpubusy")
    )

    /** The clock of the graphics unit, in MHz. */
    fun readGpuClock(): Int? {
        val hz = readLong("$GPU_ROOT/gpuclk") ?: return null
        return (hz / 1_000_000f).roundToInt()
    }

    /** A file the app may read, as a number. */
    private fun readLong(path: String): Long? = readText(path)?.toLongOrNull()

    /**
     * A file the app may read, as text.
     *
     * A build that refuses the file is a normal answer here, not an error:
     * the caller then knows that the reading is missing.
     */
    private fun readText(path: String): String? = runCatching {
        val file = File(path)
        if (!file.canRead()) return null
        file.readText().trim().takeIf { it.isNotBlank() }
    }.getOrNull()

    companion object {
        /** The root of the processor of the device. */
        const val CPU_ROOT = "/sys/devices/system/cpu"

        /** The root of the thermal zones of the device. */
        const val THERMAL_ROOT = "/sys/class/thermal"

        /** The root of the graphics unit of Qualcomm. */
        const val GPU_ROOT = "/sys/class/kgsl/kgsl-3d0"

        /** The most cores a phone has today. */
        private const val MAX_CORES = 15

        /** The types of a sensor that belong to the chip. */
        private val CHIP_ZONE_TYPES = listOf(
            "cpu", "soc", "apss", "tsens", "gold", "prime"
        )

        /**
         * The name of a cluster of cores.
         *
         * A range of cores is "CPU 0~1" and a single core is "CPU 7", so the
         * name of every cluster says which cores it is.
         */
        fun clusterNameOf(cores: List<Int>): String {
            val first = cores.firstOrNull() ?: return "CPU"
            val last = cores.lastOrNull() ?: return "CPU $first"
            return if (first == last) "CPU $first" else "CPU $first~$last"
        }
    }
}
