package com.framescope.monitor.core

/** Live frame-rate state, refreshed a few times per second. */
data class FpsState(
    val fps: Float = 0f,
    val frameTimeMs: Float = 0f,
    val history: List<Float> = emptyList(),
    val totalFrames: Int = 0,
    val jankFrames: Int = 0,
    val source: String = "-",
    val surface: String? = null,
    val valid: Boolean = false,
    /** Why there is no data, in plain words. */
    val reason: String? = null,
    /** Whether the reading can be shown at all, and why not when it cannot. */
    val availability: MetricAvailability = MetricAvailability.UNSUPPORTED,
    /** When the reading was taken, on the monotonic clock. */
    val measuredAtElapsedNanos: Long = 0L,
    /** How old the frame behind the number is, in milliseconds. */
    val sampleAgeMs: Long = 0L,
    /** The exact span of time the current frame rate was measured over. */
    val measurementWindowMs: Long = 0L,
    /** Which provider produced the reading. */
    val sourceKind: FpsSourceKind = FpsSourceKind.NONE,
    /**
     * The frame rate that one percent of the frames were slower than.
     *
     * Derived from the frame times of the window, not from an average of frame rates:
     * an average cannot describe the worst frames of a run.
     */
    val low1PercentFps: Float? = null,
    /** The frame rate that five percent of the frames were slower than. */
    val low5PercentFps: Float? = null,
    /** The app the reading belongs to. */
    val appPackage: String? = null
)

/** The provider a frame rate reading came from. */
enum class FpsSourceKind {
    NONE,
    /** Presentation timestamps of a verified foreground layer. */
    SURFACE_FLAINGER_LATENCY,
    /** SurfaceFlinger frame counters, used as an interval estimate. */
    TIMESTATS,
    /** The app's own graphics pipeline. */
    GFXINFO
}

/** Device level metrics, refreshed a couple of times per second. */
data class SystemMetrics(
    val cpuPercent: Float? = null,
    val cpuFreqMHz: Int? = null,
    val gpuName: String? = null,
    val gpuFreqMHz: Int? = null,
    val batteryLevel: Int? = null,
    val batteryTempC: Float? = null,
    val powerWatts: Float? = null,
    val ramUsedGb: Float? = null,
    val ramTotalGb: Float? = null,
    val ramPercent: Int? = null,
    val thermalMaxC: Float? = null,
    /** Android's thermal mitigation level: 0 = none, 1 = light, 2 = moderate, 3 = severe. */
    val thermalStatus: Int? = null,
    /** Warmest temperature seen since monitoring began (or since the current recording). */
    val tempPeakC: Float? = null,
    /** How far the phone has cooled from that peak, in °C. */
    val tempCooledC: Float? = null,
    val refreshHz: Float? = null,
    val updatedAt: Long = 0L,
    /** Why each metric is or is not available. A metric missing here is unavailable. */
    val availability: Map<SystemMetric, MetricAvailability> = emptyMap(),
    /** Where each metric was read from. */
    val sources: Map<SystemMetric, String> = emptyMap(),
    /** When the snapshot was taken, on the monotonic clock. */
    val measuredAtElapsedNanos: Long = 0L
) {
    /**
     * The state of one metric.
     *
     * A metric with no value and no recorded state was never collected, which is
     * different from one that was collected and is unavailable.
     */
    fun availabilityOf(metric: SystemMetric): MetricAvailability =
        availability[metric] ?: if (isPresent(metric)) {
            MetricAvailability.AVAILABLE
        } else {
            MetricAvailability.UNSUPPORTED
        }

    /** The collector that produced this metric, when it is known. */
    fun sourceOf(metric: SystemMetric): String? = sources[metric]

    private fun isPresent(metric: SystemMetric): Boolean = when (metric) {
        SystemMetric.CPU_PERCENT -> cpuPercent != null
        SystemMetric.CPU_FREQ_MHZ -> cpuFreqMHz != null
        SystemMetric.GPU_NAME -> gpuName != null
        SystemMetric.GPU_FREQ_MHZ -> gpuFreqMHz != null
        SystemMetric.BATTERY_LEVEL -> batteryLevel != null
        SystemMetric.BATTERY_TEMP_C -> batteryTempC != null
        SystemMetric.POWER_WATTS -> powerWatts != null
        SystemMetric.RAM_USED_GB -> ramUsedGb != null
        SystemMetric.RAM_TOTAL_GB -> ramTotalGb != null
        SystemMetric.RAM_PERCENT -> ramPercent != null
        SystemMetric.THERMAL_MAX_C -> thermalMaxC != null
        SystemMetric.THERMAL_STATUS -> thermalStatus != null
        SystemMetric.REFRESH_HZ -> refreshHz != null
    }
}

/**
 * Temperatures a phone can actually report.
 *
 * Several devices return a near-100 value (99.9 is common) from a thermal or
 * battery sensor that is absent or uncalibrated. A phone switches off long before
 * 100 °C, so such a reading is a placeholder, never a real heat reading. These
 * helpers discard it instead of letting it become a "peak" or a chart maximum.
 */
const val MAX_BATTERY_TEMP_C = 70f
const val MAX_HOTSPOT_TEMP_C = 85f

/** Zero is the "no reading" sentinel, so only values strictly above it count. */
fun Float?.plausibleTempC(max: Float): Float? = this?.takeIf { it > 0f && it <= max }

/**
 * The phone temperature shown to the user.
 *
 * The battery sensor is the one every phone exposes in a known unit. The maximum
 * of every thermal sensor can name a hotspot deep inside the chip, which is not
 * the temperature of the phone in the hand, so it is only a fallback. Placeholder
 * values are rejected before either source is used.
 */
fun SystemMetrics.deviceTemperatureC(): Float? =
    batteryTempC.plausibleTempC(MAX_BATTERY_TEMP_C)
        ?: thermalMaxC.plausibleTempC(MAX_HOTSPOT_TEMP_C)

/** Plain name of Android's thermal mitigation level, or "--" when the device reports none. */
fun thermalStatusLabel(status: Int?): String = when (status) {
    null -> "--"
    0 -> "None"
    1 -> "Light"
    2 -> "Moderate"
    3 -> "Severe"
    4 -> "Critical"
    5 -> "Emergency"
    else -> "Shutdown"
}

/** A recorded gameplay / app session. */
data class SessionRecord(
    val id: String,
    val appPackage: String,
    val appLabel: String,
    val startedAt: Long,
    val durationMs: Long,
    val avgFps: Float,
    val maxFps: Float,
    val minFps: Float,
    val low1Percent: Float,
    val low5Percent: Float,
    val smoothness: Float,
    val totalFrames: Int,
    val jankFrames: Int,
    val avgFrameTimeMs: Float,
    val maxFrameTimeMs: Float,
    val maxTempC: Float?,
    val avgCpu: Float?,
    val fpsSeries: List<Float>,
    val cpuSeries: List<Float>,
    val tempSeries: List<Float>,
    /** Mean frame time per second, in milliseconds - this is the jitter signal. */
    val frameTimeSeries: List<Float> = emptyList(),
    /** Battery level per second, in percent. */
    val batterySeries: List<Float> = emptyList(),
    /** Longest frame of the run, in milliseconds. */
    val longestFrameMs: Float = 0f,
    /** Seconds in which the frame rate fell below the target. */
    val dropSeconds: Int = 0,
    /**
     * The parts of the run that were measured from different apps or different sources.
     *
     * The frame rate of two providers is not the same kind of number, so the run is
     * split where the app or the provider changed instead of one series being drawn
     * across the boundary.
     */
    val segments: List<SessionSegment> = emptyList(),
    /** Seconds of the run that had no reading at all. */
    val gapSeconds: Int = 0,
    /** Highest thermal mitigation level seen in the run (0 = none). */
    val peakThermalStatus: Int? = null,
    /** Seconds in which the device reported thermal mitigation. */
    val throttledSeconds: Int = 0,
    /** Thermal mitigation level per second, for the chart. */
    val thermalStatusSeries: List<Float> = emptyList()
)

/**
 * One continuous stretch of a session that was measured from one app with one source.
 */
data class SessionSegment(
    val appPackage: String,
    val source: String,
    /** Where the segment starts, measured from the beginning of the session. */
    val startOffsetMs: Long,
    /** Where the segment ends, measured from the beginning of the session. */
    val endOffsetMs: Long,
    /** The frames that were counted in this segment. */
    val frames: Int,
    /** The average frame rate of this segment. */
    val averageFps: Float
)

/** Aggregated numbers for the session that is currently being recorded. */
data class LiveStats(
    val avgFps: Float = 0f,
    val maxFps: Float = 0f,
    val minFps: Float = 0f,
    val low1Percent: Float = 0f,
    val low5Percent: Float = 0f,
    val smoothness: Float = 0f,
    val totalFrames: Int = 0,
    val jankFrames: Int = 0,
    val avgFrameTimeMs: Float = 0f,
    val maxFrameTimeMs: Float = 0f,
    val durationMs: Long = 0L,
    val maxTempC: Float? = null,
    val avgCpu: Float? = null,
    val peakThermalStatus: Int? = null,
    val throttledSeconds: Int = 0
)


/**
 * The part of a run where the app was at its own top rate.
 *
 * A game often runs at a lower rate before the action starts - a lobby at 90
 * fps and the fight at 120 - and those slower seconds are the chosen rate,
 * not a dropped frame. The first time the run reaches its peak is where the
 * real measurement begins.
 */
fun List<Float>.steadyWindow(): List<Float> {
    if (isEmpty()) return emptyList()
    val peak = max()
    if (peak <= 0f) return this
    val index = indexOfFirst { it >= peak * 0.9f }
    return if (index <= 0) this else drop(index)
}
