package com.framescope.monitor.metrics

import com.framescope.monitor.core.FpsState
import com.framescope.monitor.core.LiveStats
import com.framescope.monitor.core.MAX_HOTSPOT_TEMP_C
import com.framescope.monitor.core.SessionRecord
import com.framescope.monitor.core.SessionSegment
import com.framescope.monitor.core.SystemMetrics
import com.framescope.monitor.core.deviceTemperatureC
import com.framescope.monitor.core.finiteOrNull
import com.framescope.monitor.core.plausibleTempC
import com.framescope.monitor.core.steadyWindow
import java.util.UUID

/**
 * Telemetry snapshot captured on every sampling interval during a session.
 */
data class SessionSample(
    val sessionId: String,
    val timestamp: Long,
    val fps: Float,
    val frameTimeMs: Float,
    val cpuPercent: Float,
    val gpuFreqMHz: Int,
    val thermalMaxC: Float,
    val batteryLevel: Int,
    val ramPercent: Int,
    val thermalStatus: Int? = null,
    /** Battery power draw at this poll, in watts, when the device reports it. */
    val powerWatts: Float? = null
)

/** Aligned per-second telemetry data bucket. */
data class SecondBucket(
    val fps: Float,
    val frameTimeMs: Float,
    val cpuPercent: Float,
    val tempC: Float,
    val batteryLevel: Float,
    val thermalStatus: Int? = null,
    val powerWatts: Float? = null
)

/**
 * Handles background sampling during recording runs and builds complete [SessionRecord] models.
 *
 * The numbers of a session are built from the frame counter of the sampler, not from the
 * frame rate of each poll:
 *
 * - The frame total is the number of frames that were actually presented, counted once
 *   each. The sampler resets its counter when the app or the provider changes, and a
 *   reset is treated as a boundary rather than as a number to subtract.
 * - The first reading of a run is the baseline of the counter, so the run counts the
 *   frames that were presented from that moment on. The frames the counter had already
 *   seen belong to whatever came before the run and are not claimed by it.
 * - The jank count is the number of frame intervals that missed the frame budget.
 * - The 1% and 5% lows come from the frame times, and the session keeps the worst window
 *   of the run.
 * - The average is the unique frames divided by the time that was actually measured.
 *   Time without a reading is a gap, and a gap is not turned into data.
 */
class SessionLogger(private val store: SessionStore) {

    @Volatile
    var isRecording = false
        private set

    private var currentSessionId = ""
    private var currentPackage = ""
    private var currentLabel = ""
    private var startedAt = 0L
    private var startBattery: Int? = null

    private val samples = ArrayList<SessionSample>()
    private val secondBuckets = ArrayList<SecondBucket>()

    private val bucketFps = ArrayList<Float>()
    private val bucketCpu = ArrayList<Float>()
    private val bucketTemp = ArrayList<Float>()
    private val bucketFrameTime = ArrayList<Float>()
    private val bucketBattery = ArrayList<Float>()
    private val bucketThermalStatus = ArrayList<Int>()
    private val bucketPower = ArrayList<Float>()

    private var lastBucketSec = -1L
    // The privileged poll is slower than the one-second buckets, so a level stays
    // in force until the next reading instead of leaving a fake zero gap.
    private var lastThermalStatus: Int? = null

    private var lastTotalFrames = -1
    private var lastJankFrames = -1
    private var accumulatedFrames = 0
    private var accumulatedJank = 0
    private var observedSamples = 0
    private var maxFrameTimeSeen = 0f
    private var low1Seen = Float.MAX_VALUE
    private var low5Seen = Float.MAX_VALUE
    private var minFpsSeen = Float.MAX_VALUE
    private var maxFpsSeen = 0f

    private val segments = ArrayList<SessionSegment>()
    private var segmentPackage = ""
    private var segmentSource = ""
    private var segmentStartMs = 0L
    private var segmentFrames = 0
    private var segmentFpsSum = 0f
    private var segmentSamples = 0

    private var firstValidAt = 0L
    private var lastValidAt = 0L
    private var gapMs = 0L

    @Synchronized
    fun start(packageName: String, label: String, initialBattery: Int?) {
        currentSessionId = UUID.randomUUID().toString()
        currentPackage = packageName
        currentLabel = label
        startedAt = System.currentTimeMillis()
        startBattery = initialBattery
        isRecording = true

        samples.clear()
        secondBuckets.clear()

        bucketFps.clear()
        bucketCpu.clear()
        bucketTemp.clear()
        bucketFrameTime.clear()
        bucketBattery.clear()
        bucketThermalStatus.clear()
        bucketPower.clear()

        lastBucketSec = startedAt / 1000
        lastThermalStatus = null
        lastTotalFrames = -1
        lastJankFrames = -1
        accumulatedFrames = 0
        accumulatedJank = 0
        observedSamples = 0
        maxFrameTimeSeen = 0f
        low1Seen = Float.MAX_VALUE
        low5Seen = Float.MAX_VALUE
        minFpsSeen = Float.MAX_VALUE
        maxFpsSeen = 0f

        segments.clear()
        segmentPackage = ""
        segmentSource = ""
        segmentStartMs = 0L
        segmentFrames = 0
        segmentFpsSum = 0f
        segmentSamples = 0

        firstValidAt = 0L
        lastValidAt = 0L
        gapMs = 0L
    }

    @Synchronized
    fun onSample(fpsState: FpsState, metrics: SystemMetrics, targetFps: Int) {
        if (!isRecording || !fpsState.valid) return

        val now = System.currentTimeMillis()
        val nowSec = now / 1000

        val fps = fpsState.fps.finiteOrNull() ?: 0f
        val frameTime = fpsState.frameTimeMs.finiteOrNull() ?: 0f
        val cpu = metrics.cpuPercent.finiteOrNull() ?: 0f
        val gpuFreq = metrics.gpuFreqMHz ?: 0
        val temp = metrics.deviceTemperatureC() ?: 0f
        val battery = metrics.batteryLevel ?: 0
        val ram = metrics.ramPercent ?: 0
        val powerWatts = metrics.powerWatts.finiteOrNull()?.takeIf { it >= 0f }
        val thermalStatus = metrics.thermalStatus?.takeIf { it in 0..6 }
        if (thermalStatus != null) lastThermalStatus = thermalStatus
        val bucketStatus = thermalStatus ?: lastThermalStatus

        samples.add(
            SessionSample(
                sessionId = currentSessionId,
                timestamp = now,
                fps = fps,
                frameTimeMs = frameTime,
                cpuPercent = cpu,
                gpuFreqMHz = gpuFreq,
                thermalMaxC = temp,
                batteryLevel = battery,
                ramPercent = ram,
                thermalStatus = thermalStatus,
                powerWatts = powerWatts
            )
        )
        observedSamples++

        // The frames that were presented since the last poll. The counter of the sampler
        // only ever grows while it is looking at the same target, so a target change
        // shows up as a number that went backwards and is treated as a boundary.
        val frameDelta = if (lastTotalFrames >= 0 && fpsState.totalFrames >= lastTotalFrames) {
            fpsState.totalFrames - lastTotalFrames
        } else {
            0
        }
        accumulatedFrames += frameDelta
        lastTotalFrames = fpsState.totalFrames

        if (lastJankFrames >= 0 && fpsState.jankFrames >= lastJankFrames) {
            accumulatedJank += fpsState.jankFrames - lastJankFrames
        }
        lastJankFrames = fpsState.jankFrames

        if (fps > 0f) {
            if (fps > maxFpsSeen) maxFpsSeen = fps
            if (fps < minFpsSeen) minFpsSeen = fps
        }
        fpsState.low1PercentFps?.let { if (it > 0f && it < low1Seen) low1Seen = it }
        fpsState.low5PercentFps?.let { if (it > 0f && it < low5Seen) low5Seen = it }
        if (frameTime > maxFrameTimeSeen) {
            maxFrameTimeSeen = frameTime
        }

        // A stretch of time without a reading is a gap, not a slow second.
        if (firstValidAt == 0L) {
            firstValidAt = now
        } else {
            val delta = now - lastValidAt
            if (delta > GAP_THRESHOLD_MS) {
                gapMs += delta - GAP_THRESHOLD_MS
            }
        }
        lastValidAt = now

        updateSegment(fpsState, fps, now, frameDelta)

        bucketFps.add(fps)
        bucketFrameTime.add(frameTime)
        if (metrics.cpuPercent != null) bucketCpu.add(cpu)
        if (temp > 0f) bucketTemp.add(temp)
        if (battery > 0) bucketBattery.add(battery.toFloat())
        if (bucketStatus != null) bucketThermalStatus.add(bucketStatus)
        if (powerWatts != null) bucketPower.add(powerWatts)

        if (nowSec > lastBucketSec) {
            flushBucket()
            // Gap time is removed from the averages, not filled with a copy of the
            // last reading. A copied bucket looks like measured data on the chart.
            lastBucketSec = nowSec
        }
    }

    /**
     * Keeps the run split into the stretches that were measured the same way.
     *
     * The frame rate of two providers is not the same kind of number, so a change of app
     * or of provider ends one segment and starts another.
     */
    private fun updateSegment(fpsState: FpsState, fps: Float, now: Long, frameDelta: Int) {
        val pkg = fpsState.appPackage ?: currentPackage
        val source = fpsState.sourceKind.name
        val offset = now - startedAt

        if (segmentPackage != pkg || segmentSource != source) {
            closeSegment(offset)
            segmentPackage = pkg
            segmentSource = source
            segmentStartMs = offset
            segmentFrames = 0
            segmentFpsSum = 0f
            segmentSamples = 0
        }

        segmentSamples++
        segmentFpsSum += fps
        segmentFrames += frameDelta
    }

    private fun closeSegment(offset: Long) {
        if (segmentPackage.isEmpty() && segmentSource.isEmpty()) return
        if (segmentSamples == 0) return
        segments.add(
            SessionSegment(
                appPackage = segmentPackage,
                source = segmentSource,
                startOffsetMs = segmentStartMs,
                endOffsetMs = offset,
                frames = segmentFrames,
                averageFps = segmentFpsSum / segmentSamples
            )
        )
    }

    private fun flushBucket() {
        if (bucketFps.isEmpty()) return
        val fps = bucketFps.average().toFloat()
        val frameTime = if (bucketFrameTime.isNotEmpty()) bucketFrameTime.average().toFloat() else (if (fps > 0f) 1000f / fps else 16.6f)
        val cpu = if (bucketCpu.isNotEmpty()) bucketCpu.average().toFloat() else 0f
        val temp = if (bucketTemp.isNotEmpty()) bucketTemp.average().toFloat() else 0f
        val battery = if (bucketBattery.isNotEmpty()) bucketBattery.last() else (startBattery?.toFloat() ?: 0f)
        // One level per second: the worst state in that second, so a brief spike
        // is not averaged away into "none".
        val thermalStatus = bucketThermalStatus.maxOrNull()
        val power = if (bucketPower.isNotEmpty()) bucketPower.average().toFloat() else null

        secondBuckets.add(
            SecondBucket(
                fps = fps,
                frameTimeMs = frameTime,
                cpuPercent = cpu,
                tempC = temp,
                batteryLevel = battery,
                thermalStatus = thermalStatus,
                powerWatts = power
            )
        )

        bucketFps.clear()
        bucketFrameTime.clear()
        bucketCpu.clear()
        bucketTemp.clear()
        bucketBattery.clear()
        bucketThermalStatus.clear()
        bucketPower.clear()
    }

    @Synchronized
    fun currentLiveStats(): LiveStats {
        if (!isRecording || samples.isEmpty()) return LiveStats()
        val measuredMs = measuredDurationMs()
        val avgFps = if (measuredMs > 0L) {
            (accumulatedFrames.toDouble() * 1000.0 / measuredMs).toFloat()
        } else {
            0f
        }
        val smoothness = if (accumulatedFrames > 0) {
            ((1f - (accumulatedJank.toFloat() / accumulatedFrames)) * 100f).coerceIn(0f, 100f)
        } else {
            100f
        }
        val avgFt = samples.map { it.frameTimeMs }.average().toFloat()
        val maxTemp = samples.mapNotNull { it.thermalMaxC.plausibleTempC(MAX_HOTSPOT_TEMP_C) }.maxOrNull()
        val avgCpu = samples.map { it.cpuPercent }.filter { it > 0f }.takeIf { it.isNotEmpty() }?.average()?.toFloat()
        val peakThermalStatus = samples.mapNotNull { it.thermalStatus }.maxOrNull()
        val throttledSeconds = secondBuckets.count { (it.thermalStatus ?: 0) > 0 }

        return LiveStats(
            avgFps = avgFps,
            maxFps = if (maxFpsSeen == 0f) 0f else maxFpsSeen,
            minFps = if (minFpsSeen == Float.MAX_VALUE) 0f else minFpsSeen,
            low1Percent = if (low1Seen == Float.MAX_VALUE) 0f else low1Seen,
            low5Percent = if (low5Seen == Float.MAX_VALUE) 0f else low5Seen,
            smoothness = smoothness,
            totalFrames = accumulatedFrames,
            jankFrames = accumulatedJank,
            avgFrameTimeMs = avgFt,
            maxFrameTimeMs = maxFrameTimeSeen,
            durationMs = measuredMs,
            maxTempC = maxTemp,
            avgCpu = avgCpu,
            peakThermalStatus = peakThermalStatus,
            throttledSeconds = throttledSeconds
        )
    }

    @Synchronized
    fun stop(targetFps: Int): SessionRecord? {
        if (!isRecording) return null
        isRecording = false
        flushBucket()
        closeSegment(System.currentTimeMillis() - startedAt)

        val duration = System.currentTimeMillis() - startedAt
        if (samples.size < 3 && secondBuckets.size < 2) {
            return null
        }

        val allFps = samples.map { it.fps }.filter { it > 0f }
        if (allFps.isEmpty()) return null

        val fpsSeries = secondBuckets.map { it.fps }
        val steady = fpsSeries.steadyWindow()
        val evaluationFps = if (steady.isNotEmpty()) steady else allFps
        val sortedFps = evaluationFps.sorted()

        // The average is the frames that were presented divided by the time that was
        // actually measured, not the average of the per second readings.
        val measuredMs = measuredDurationMs()
        val avgFps = if (measuredMs > 0L) {
            (accumulatedFrames.toDouble() * 1000.0 / measuredMs).toFloat()
        } else {
            sortedFps.average().toFloat()
        }
        val maxFps = if (maxFpsSeen == 0f) sortedFps.last() else maxFpsSeen
        val minFps = if (minFpsSeen == Float.MAX_VALUE) sortedFps.first() else minFpsSeen

        // The 1% and 5% lows are the worst window of the run, taken from the frame times.
        val low1 = if (low1Seen == Float.MAX_VALUE) sortedFps.first() else low1Seen
        val low5 = if (low5Seen == Float.MAX_VALUE) sortedFps.first() else low5Seen

        val totalFrames = accumulatedFrames
        val smoothness = if (totalFrames > 0) {
            ((1f - (accumulatedJank.toFloat() / totalFrames)) * 100f).coerceIn(0f, 100f)
        } else 100f

        val avgFrameTime = if (samples.isNotEmpty()) {
            samples.map { it.frameTimeMs }.average().toFloat()
        } else if (avgFps > 0f) 1000f / avgFps else 16.6f

        val maxTemp = samples.mapNotNull { it.thermalMaxC.plausibleTempC(MAX_HOTSPOT_TEMP_C) }.maxOrNull()
        val avgCpu = samples.map { it.cpuPercent }.filter { it > 0f }.takeIf { it.isNotEmpty() }?.average()?.toFloat()
        val peakThermalStatus = samples.mapNotNull { it.thermalStatus }.maxOrNull()
        val throttledSeconds = secondBuckets.count { (it.thermalStatus ?: 0) > 0 }
        // A device that never reports a level gets no chart, not a flat line of zeros.
        val thermalStatusSeries = if (samples.any { it.thermalStatus != null }) {
            secondBuckets.map { (it.thermalStatus ?: 0).toFloat() }
        } else emptyList()

        // Jitter is the mean change between consecutive frame times. A steady 60 fps
        // and a stuttering 60 fps have the same average but very different jitter.
        val frameTimes = samples.map { it.frameTimeMs }.filter { it > 0f }
        val jitter = if (frameTimes.size >= 2) {
            (1 until frameTimes.size)
                .map { kotlin.math.abs(frameTimes[it] - frameTimes[it - 1]) }
                .average().toFloat()
        } else null
        // Variance describes how far the per-second rate wandered from its mean.
        val steadyFps = secondBuckets.map { it.fps }.filter { it > 0f }
        val variance = if (steadyFps.size >= 2) {
            val mean = steadyFps.average()
            steadyFps.map { (it - mean) * (it - mean) }.average().toFloat()
        } else null
        val avgPower = secondBuckets.mapNotNull { it.powerWatts }
            .takeIf { it.isNotEmpty() }?.average()?.toFloat()

        val record = SessionRecord(
            id = currentSessionId,
            appPackage = currentPackage,
            appLabel = currentLabel.ifBlank { currentPackage.substringAfterLast('.') },
            startedAt = startedAt,
            durationMs = duration,
            avgFps = avgFps,
            maxFps = maxFps,
            minFps = minFps,
            low1Percent = low1,
            low5Percent = low5,
            smoothness = smoothness,
            totalFrames = totalFrames,
            jankFrames = accumulatedJank,
            avgFrameTimeMs = avgFrameTime,
            maxFrameTimeMs = maxFrameTimeSeen,
            maxTempC = maxTemp,
            avgCpu = avgCpu,
            fpsSeries = fpsSeries,
            cpuSeries = secondBuckets.map { it.cpuPercent },
            tempSeries = secondBuckets.map { it.tempC },
            frameTimeSeries = secondBuckets.map { it.frameTimeMs },
            batterySeries = secondBuckets.map { it.batteryLevel },
            longestFrameMs = maxFrameTimeSeen,
            dropSeconds = evaluationFps.count { it < targetFps },
            segments = segments.toList(),
            gapSeconds = (gapMs / 1000L).toInt(),
            peakThermalStatus = peakThermalStatus,
            throttledSeconds = throttledSeconds,
            thermalStatusSeries = thermalStatusSeries,
            jitterMs = jitter,
            fpsVariance = variance,
            avgPowerW = avgPower
        )

        store.add(record)
        store.addSamples(samples)
        return record
    }

    /** The time of the run that carried a reading, without the gaps. */
    private fun measuredDurationMs(): Long {
        if (firstValidAt == 0L || lastValidAt <= firstValidAt) return 0L
        return (lastValidAt - firstValidAt - gapMs).coerceAtLeast(0L)
    }

    companion object {
        /**
         * Two readings further apart than this are a gap rather than a slow second.
         *
         * The sampler polls several times a second, so anything beyond two seconds means
         * the app went away or the source stopped answering.
         */
        private const val GAP_THRESHOLD_MS = 2_000L
    }
}
