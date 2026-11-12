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
    val reason: String? = null
)

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
    val refreshHz: Float? = null,
    val updatedAt: Long = 0L
)

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
    val dropSeconds: Int = 0
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
    val avgCpu: Float? = null
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
