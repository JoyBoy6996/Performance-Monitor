package com.framescope.monitor.metrics

import com.framescope.monitor.core.FpsState
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
    private val secondFps = ArrayList<Float>()
    private val secondCpu = ArrayList<Float>()
    private val secondTemp = ArrayList<Float>()
    private val secondFrameTime = ArrayList<Float>()
    private val secondBattery = ArrayList<Float>()

    private val bucketFps = ArrayList<Float>()
    private val bucketCpu = ArrayList<Float>()
    private val bucketTemp = ArrayList<Float>()
    private val bucketFrameTime = ArrayList<Float>()
    private val bucketBattery = ArrayList<Float>()

    private var lastBucketSec = -1L
    private var totalFramesObserved = 0
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
        secondFps.clear()
        secondCpu.clear()
        secondTemp.clear()
        secondFrameTime.clear()
        secondBattery.clear()

        bucketFps.clear()
        bucketCpu.clear()
        bucketTemp.clear()
        bucketFrameTime.clear()
        bucketBattery.clear()

        lastBucketSec = startedAt / 1000
        totalFramesObserved = 0
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
        store.addSample(sample)

        totalFramesObserved++
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
            lastBucketSec = nowSec
        }
    }

    private fun flushBucket() {
        if (bucketFps.isNotEmpty()) {
            secondFps.add(bucketFps.average().toFloat())
            bucketFps.clear()
        }
        if (bucketFrameTime.isNotEmpty()) {
            secondFrameTime.add(bucketFrameTime.average().toFloat())
            bucketFrameTime.clear()
        }
        if (bucketCpu.isNotEmpty()) {
            secondCpu.add(bucketCpu.average().toFloat())
            bucketCpu.clear()
        }
        if (bucketTemp.isNotEmpty()) {
            secondTemp.add(bucketTemp.average().toFloat())
            bucketTemp.clear()
        }
        if (bucketBattery.isNotEmpty()) {
            secondBattery.add(bucketBattery.last())
            bucketBattery.clear()
        }
    }

    @Synchronized
    fun stop(targetFps: Int): SessionRecord? {
        if (!isRecording) return null
        isRecording = false
        flushBucket()

        val duration = System.currentTimeMillis() - startedAt
        if (samples.size < 3 && secondFps.size < 2) {
            return null
        }

        val allFps = samples.map { it.fps }.filter { it > 0f }.sorted()
        if (allFps.isEmpty()) return null

        val avgFps = allFps.average().toFloat()
        val maxFps = allFps.last()
        val minFps = allFps.first()

        // 1% low and 5% low
        val p1Index = ((allFps.size - 1) * 0.01f).toInt().coerceIn(0, allFps.size - 1)
        val p5Index = ((allFps.size - 1) * 0.05f).toInt().coerceIn(0, allFps.size - 1)
        val low1 = allFps[p1Index]
        val low5 = allFps[p5Index]

        val totalFrames = if (totalFramesObserved > 0) totalFramesObserved else samples.size
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
            fpsSeries = secondFps.toList(),
            cpuSeries = secondCpu.toList(),
            tempSeries = secondTemp.toList(),
            frameTimeSeries = secondFrameTime.toList(),
            batterySeries = secondBattery.toList(),
            longestFrameMs = maxFrameTimeSeen,
            dropSeconds = secondFps.steadyWindow().count { it < targetFps }
        )

        store.add(record)
        return record
    }
}
