package com.framescope.monitor.metrics

import com.framescope.monitor.core.FpsState
import com.framescope.monitor.core.LiveStats
import com.framescope.monitor.core.SessionRecord
import com.framescope.monitor.core.SystemMetrics
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
    val ramPercent: Int
)

/** Aligned per-second telemetry data bucket. */
data class SecondBucket(
    val fps: Float,
    val frameTimeMs: Float,
    val cpuPercent: Float,
    val tempC: Float,
    val batteryLevel: Float
)

/**
 * Handles background sampling during recording runs and builds complete [SessionRecord] models.
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

    private var lastBucketSec = -1L
    private var lastFpsStateTotalFrames = -1
    private var accumulatedRealFrames = 0
    private var observedSamples = 0
    private var jankFramesCount = 0
    private var maxFrameTimeSeen = 0f

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

        lastBucketSec = startedAt / 1000
        lastFpsStateTotalFrames = -1
        accumulatedRealFrames = 0
        observedSamples = 0
        jankFramesCount = 0
        maxFrameTimeSeen = 0f
    }

    @Synchronized
    fun onSample(fpsState: FpsState, metrics: SystemMetrics, targetFps: Int) {
        if (!isRecording || !fpsState.valid) return

        val now = System.currentTimeMillis()
        val nowSec = now / 1000

        val fps = fpsState.fps
        val frameTime = fpsState.frameTimeMs
        val cpu = metrics.cpuPercent ?: 0f
        val gpuFreq = metrics.gpuFreqMHz ?: 0
        val temp = metrics.thermalMaxC ?: (metrics.batteryTempC ?: 0f)
        val battery = metrics.batteryLevel ?: 0
        val ram = metrics.ramPercent ?: 0

        val sample = SessionSample(
            sessionId = currentSessionId,
            timestamp = now,
            fps = fps,
            frameTimeMs = frameTime,
            cpuPercent = cpu,
            gpuFreqMHz = gpuFreq,
            thermalMaxC = temp,
            batteryLevel = battery,
            ramPercent = ram
        )
        samples.add(sample)

        observedSamples++
        if (lastFpsStateTotalFrames >= 0 && fpsState.totalFrames >= lastFpsStateTotalFrames) {
            accumulatedRealFrames += (fpsState.totalFrames - lastFpsStateTotalFrames)
        } else if (fpsState.totalFrames > 0 && lastFpsStateTotalFrames < 0) {
            accumulatedRealFrames += 1
        }
        lastFpsStateTotalFrames = fpsState.totalFrames

        val targetFrameTimeMs = if (targetFps > 0) 1000f / targetFps else 16.66f
        if (frameTime > targetFrameTimeMs * 1.5f || (fps > 0 && fps < targetFps * 0.85f)) {
            jankFramesCount++
        }
        if (frameTime > maxFrameTimeSeen) {
            maxFrameTimeSeen = frameTime
        }

        bucketFps.add(fps)
        bucketFrameTime.add(frameTime)
        if (metrics.cpuPercent != null) bucketCpu.add(cpu)
        if (temp > 0f) bucketTemp.add(temp)
        if (battery > 0) bucketBattery.add(battery.toFloat())

        if (nowSec > lastBucketSec) {
            flushBucket()
            // Fill any missed seconds if sampler stalled
            val gap = (nowSec - lastBucketSec - 1).coerceAtMost(30)
            if (gap > 0 && secondBuckets.isNotEmpty()) {
                val last = secondBuckets.last()
                repeat(gap.toInt()) {
                    secondBuckets.add(last)
                }
            }
            lastBucketSec = nowSec
        }
    }

    private fun flushBucket() {
        if (bucketFps.isEmpty()) return
        val fps = bucketFps.average().toFloat()
        val frameTime = if (bucketFrameTime.isNotEmpty()) bucketFrameTime.average().toFloat() else (if (fps > 0f) 1000f / fps else 16.6f)
        val cpu = if (bucketCpu.isNotEmpty()) bucketCpu.average().toFloat() else 0f
        val temp = if (bucketTemp.isNotEmpty()) bucketTemp.average().toFloat() else 0f
        val battery = if (bucketBattery.isNotEmpty()) bucketBattery.last() else (startBattery?.toFloat() ?: 0f)

        secondBuckets.add(SecondBucket(fps, frameTime, cpu, temp, battery))

        bucketFps.clear()
        bucketFrameTime.clear()
        bucketCpu.clear()
        bucketTemp.clear()
        bucketBattery.clear()
    }

    @Synchronized
    fun currentLiveStats(): LiveStats {
        if (!isRecording || samples.isEmpty()) return LiveStats()
        val allFps = samples.map { it.fps }.filter { it > 0f }.sorted()
        val avgFps = if (allFps.isNotEmpty()) allFps.average().toFloat() else 0f
        val maxFps = allFps.lastOrNull() ?: 0f
        val minFps = allFps.firstOrNull() ?: 0f
        val p1Index = ((allFps.size - 1) * 0.01f).toInt().coerceIn(0, (allFps.size - 1).coerceAtLeast(0))
        val p5Index = ((allFps.size - 1) * 0.05f).toInt().coerceIn(0, (allFps.size - 1).coerceAtLeast(0))
        val low1 = if (allFps.isNotEmpty()) allFps[p1Index] else 0f
        val low5 = if (allFps.isNotEmpty()) allFps[p5Index] else 0f
        val frames = if (accumulatedRealFrames > 0) accumulatedRealFrames else observedSamples
        val smoothness = if (frames > 0) ((1f - (jankFramesCount.toFloat() / frames)) * 100f).coerceIn(0f, 100f) else 100f
        val avgFt = if (samples.isNotEmpty()) samples.map { it.frameTimeMs }.average().toFloat() else 0f
        val maxTemp = samples.map { it.thermalMaxC }.filter { it > 0f }.maxOrNull()
        val avgCpu = samples.map { it.cpuPercent }.filter { it > 0f }.takeIf { it.isNotEmpty() }?.average()?.toFloat()

        return LiveStats(
            avgFps = avgFps,
            maxFps = maxFps,
            minFps = minFps,
            low1Percent = low1,
            low5Percent = low5,
            smoothness = smoothness,
            totalFrames = frames,
            jankFrames = jankFramesCount,
            avgFrameTimeMs = avgFt,
            maxFrameTimeMs = maxFrameTimeSeen,
            durationMs = System.currentTimeMillis() - startedAt,
            maxTempC = maxTemp,
            avgCpu = avgCpu
        )
    }

    @Synchronized
    fun stop(targetFps: Int): SessionRecord? {
        if (!isRecording) return null
        isRecording = false
        flushBucket()

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

        val avgFps = sortedFps.average().toFloat()
        val maxFps = sortedFps.last()
        val minFps = sortedFps.first()

        // 1% low and 5% low
        val p1Index = ((sortedFps.size - 1) * 0.01f).toInt().coerceIn(0, sortedFps.size - 1)
        val p5Index = ((sortedFps.size - 1) * 0.05f).toInt().coerceIn(0, sortedFps.size - 1)
        val low1 = sortedFps[p1Index]
        val low5 = sortedFps[p5Index]

        val totalFrames = if (accumulatedRealFrames > 0) accumulatedRealFrames else observedSamples
        val smoothness = if (totalFrames > 0) {
            ((1f - (jankFramesCount.toFloat() / totalFrames)) * 100f).coerceIn(0f, 100f)
        } else 100f

        val avgFrameTime = if (samples.isNotEmpty()) {
            samples.map { it.frameTimeMs }.average().toFloat()
        } else if (avgFps > 0f) 1000f / avgFps else 16.6f

        val maxTemp = samples.map { it.thermalMaxC }.filter { it > 0f }.maxOrNull()
        val avgCpu = samples.map { it.cpuPercent }.filter { it > 0f }.takeIf { it.isNotEmpty() }?.average()?.toFloat()

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
            jankFrames = jankFramesCount,
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
            dropSeconds = evaluationFps.count { it < targetFps }
        )

        store.add(record)
        store.addSamples(samples)
        return record
    }
}
