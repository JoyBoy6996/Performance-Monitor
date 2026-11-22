package com.framescope.monitor.metrics

import com.framescope.monitor.core.CapChange
import com.framescope.monitor.core.CpuGroupMetric
import com.framescope.monitor.core.FpsState
import com.framescope.monitor.core.LiveStats
import com.framescope.monitor.core.MAX_HOTSPOT_TEMP_C
import com.framescope.monitor.core.SessionDeviceInfo
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
    val powerWatts: Float? = null,
    val cpuFreqMHz: Int = 0,
    val gpuFreqMHz: Int = 0,
    val cpuTempC: Float = 0f,
    /** Max of all plausible thermal sensors; not a battery reading. */
    val socTempC: Float = 0f,
    val gpuPercent: Float = 0f,
    val cpuGroups: List<CpuGroupMetric> = emptyList(),
    /** One load per real core, ordered by core number. */
    val coreLoads: List<Float?> = emptyList(),
    /** One clock per real core, in MHz, in the same order as [coreLoads]. */
    val coreFreqs: List<Int?> = emptyList(),
    /** Memory controller clock in MHz; zero means the device did not report one. */
    val ddrFreqMHz: Int = 0,
    /** Busiest threads at the end of this second. */
    val threads: List<com.framescope.monitor.core.ThreadLoad> = emptyList(),
    /** Mean battery current in mA; null when the platform does not report it. */
    val currentMa: Float? = null,
    val voltageMv: Int? = null,
    val cycles: Int? = null,
    /** The observed cap at the end of this second; zero means not confirmed yet. */
    val capFps: Float = 0f,
    /** "heat" only while thermal mitigation was active and the rate had dropped. */
    val capCause: String? = null
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
    private var deviceInfo = SessionDeviceInfo()

    private val samples = ArrayList<SessionSample>()
    private val secondBuckets = ArrayList<SecondBucket>()

    private val bucketFps = ArrayList<Float>()
    private val bucketCpu = ArrayList<Float>()
    private val bucketTemp = ArrayList<Float>()
    private val bucketFrameTime = ArrayList<Float>()
    private val bucketBattery = ArrayList<Float>()
    private val bucketThermalStatus = ArrayList<Int>()
    private val bucketPower = ArrayList<Float>()
    private val bucketCpuFreq = ArrayList<Int>()
    private val bucketGpuFreq = ArrayList<Int>()
    private val bucketCpuTemp = ArrayList<Float>()
    private val bucketSocTemp = ArrayList<Float>()
    private val bucketGpuPercent = ArrayList<Float>()
    private val bucketCpuGroups = ArrayList<List<CpuGroupMetric>>()
    private val bucketCoreLoads = ArrayList<List<Float?>>()
    private val bucketCoreFreqs = ArrayList<List<Int?>>()
    private val bucketDdrFreq = ArrayList<Int>()
    private val bucketThreads = ArrayList<List<com.framescope.monitor.core.ThreadLoad>>()
    private val bucketCurrent = ArrayList<Float>()
    private var lastVoltageMv: Int? = null
    private var lastCycles: Int? = null
    private val bucketCap = ArrayList<Int>()
    private val bucketCapCause = ArrayList<String>()
    private val capChanges = ArrayList<CapChange>()
    private var lastCapFps: Int? = null

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
    fun start(
        packageName: String,
        label: String,
        initialBattery: Int?,
        device: SessionDeviceInfo = SessionDeviceInfo()
    ) {
        currentSessionId = UUID.randomUUID().toString()
        currentPackage = packageName
        currentLabel = label
        startedAt = System.currentTimeMillis()
        startBattery = initialBattery
        deviceInfo = device
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
        bucketCpuFreq.clear()
        bucketGpuFreq.clear()
        bucketCpuTemp.clear()
        bucketSocTemp.clear()
        bucketGpuPercent.clear()
        bucketCpuGroups.clear()
        bucketCoreLoads.clear()
        bucketCoreFreqs.clear()
        bucketDdrFreq.clear()
        bucketThreads.clear()
        bucketCurrent.clear()
        lastVoltageMv = null
        lastCycles = null
        bucketCap.clear()
        bucketCapCause.clear()
        capChanges.clear()
        lastCapFps = null

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

        // The sampler has already confirmed this rate over several polls. A change
        // here is a real cap change - heat dropping 120 to 90, or the player setting
        // 120 again - and is stored with its second so the graph can be read back.
        val capFps = fpsState.detectedCapFps?.takeIf { it > 0 }
        val capCause = fpsState.capCause?.takeIf { it == "heat" || it == "measured" }
        if (capFps != null && capFps != lastCapFps) {
            capChanges.add(
                CapChange(
                    second = ((now - startedAt) / 1000L).toInt().coerceAtLeast(0),
                    fromFps = lastCapFps,
                    toFps = capFps,
                    thermalStatus = thermalStatus,
                    cause = if (capCause == "heat") "heat" else "measured"
                )
            )
            lastCapFps = capFps
        }

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
        metrics.cpuFreqMHz?.let { if (it > 0) bucketCpuFreq.add(it) }
        metrics.gpuFreqMHz?.let { if (it > 0) bucketGpuFreq.add(it) }
        metrics.cpuTempC?.plausibleTempC(MAX_HOTSPOT_TEMP_C)?.let { bucketCpuTemp.add(it) }
        metrics.thermalMaxC?.plausibleTempC(MAX_HOTSPOT_TEMP_C)?.let { bucketSocTemp.add(it) }
        metrics.gpuPercent?.finiteOrNull()?.let { bucketGpuPercent.add(it) }
        if (metrics.cpuGroups.isNotEmpty()) bucketCpuGroups.add(metrics.cpuGroups)
        if (metrics.cpuCoreLoadPct.any { it != null }) bucketCoreLoads.add(metrics.cpuCoreLoadPct)
        if (metrics.cpuCoreFreqMHz.any { it != null }) bucketCoreFreqs.add(metrics.cpuCoreFreqMHz)
        metrics.ddrFreqMHz?.takeIf { it > 0 }?.let { bucketDdrFreq.add(it) }
        if (metrics.threadLoads.isNotEmpty()) bucketThreads.add(metrics.threadLoads)
        metrics.batteryCurrentMa?.finiteOrNull()?.let { bucketCurrent.add(it) }
        metrics.batteryVoltageMv?.takeIf { it > 0 }?.let { lastVoltageMv = it }
        metrics.batteryCycles?.takeIf { it >= 0 }?.let { lastCycles = it }
        if (capFps != null) bucketCap.add(capFps)
        if (capFps != null && capCause != null) bucketCapCause.add(capCause)

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
        val averageCpuGroups = averageCpuGroups(bucketCpuGroups)
        val coreLoads = averageNullableFloats(bucketCoreLoads)
        val coreFreqs = averageNullableInts(bucketCoreFreqs)
        val ddr = if (bucketDdrFreq.isEmpty()) 0 else bucketDdrFreq.average().toInt()
        val currentMa = if (bucketCurrent.isEmpty()) null else bucketCurrent.average().toFloat()
        val threads = averageThreads(bucketThreads)
        // The last known cap is carried forward: a poll that said nothing does not
        // mean the game stopped being capped. Zero is kept only until one is known.
        val cap = bucketCap.lastOrNull() ?: lastCapFps ?: 0
        val capCause = bucketCapCause.lastOrNull()

        secondBuckets.add(
            SecondBucket(
                fps = fps,
                frameTimeMs = frameTime,
                cpuPercent = cpu,
                tempC = temp,
                batteryLevel = battery,
                thermalStatus = thermalStatus,
                powerWatts = power,
                cpuFreqMHz = if (bucketCpuFreq.isEmpty()) 0 else bucketCpuFreq.average().toInt(),
                gpuFreqMHz = if (bucketGpuFreq.isEmpty()) 0 else bucketGpuFreq.average().toInt(),
                cpuTempC = if (bucketCpuTemp.isEmpty()) 0f else bucketCpuTemp.average().toFloat(),
                socTempC = if (bucketSocTemp.isEmpty()) 0f else bucketSocTemp.average().toFloat(),
                gpuPercent = if (bucketGpuPercent.isEmpty()) 0f else bucketGpuPercent.average().toFloat(),
                cpuGroups = averageCpuGroups,
                coreLoads = coreLoads,
                coreFreqs = coreFreqs,
                ddrFreqMHz = ddr,
                threads = threads,
                currentMa = currentMa,
                voltageMv = lastVoltageMv,
                cycles = lastCycles,
                capFps = cap.toFloat(),
                capCause = capCause
            )
        )

        bucketFps.clear()
        bucketFrameTime.clear()
        bucketCpu.clear()
        bucketTemp.clear()
        bucketBattery.clear()
        bucketThermalStatus.clear()
        bucketPower.clear()
        bucketCpuFreq.clear()
        bucketGpuFreq.clear()
        bucketCpuTemp.clear()
        bucketSocTemp.clear()
        bucketGpuPercent.clear()
        bucketCpuGroups.clear()
        bucketCoreLoads.clear()
        bucketCoreFreqs.clear()
        bucketDdrFreq.clear()
        bucketThreads.clear()
        bucketCurrent.clear()
        bucketCap.clear()
        bucketCapCause.clear()
    }

    /** Averages one value per core across all polls in a second. */
    private fun averageNullableFloats(rows: List<List<Float?>>): List<Float?> {
        if (rows.isEmpty()) return emptyList()
        val size = rows.maxOf { it.size }
        return (0 until size).map { i ->
            val values = rows.mapNotNull { it.getOrNull(i)?.finiteOrNull() }
            if (values.isEmpty()) null else values.average().toFloat()
        }
    }

    /** Same as [averageNullableFloats] for the integer clock counters. */
    private fun averageNullableInts(rows: List<List<Int?>>): List<Int?> {
        if (rows.isEmpty()) return emptyList()
        val size = rows.maxOf { it.size }
        return (0 until size).map { i ->
            val values = rows.mapNotNull { it.getOrNull(i)?.takeIf { value -> value > 0 } }
            if (values.isEmpty()) null else values.average().toInt()
        }
    }

    /** Keeps the busiest threads in a stable order so their colours do not jump. */
    private fun averageThreads(rows: List<List<com.framescope.monitor.core.ThreadLoad>>):
        List<com.framescope.monitor.core.ThreadLoad> {
        if (rows.isEmpty()) return emptyList()
        return rows.first().mapNotNull { first ->
            val values = rows.mapNotNull { row ->
                row.firstOrNull { it.name == first.name }?.percent?.finiteOrNull()
            }
            if (values.isEmpty()) {
                null
            } else {
                com.framescope.monitor.core.ThreadLoad(first.name, values.average().toFloat())
            }
        }
    }

    /** Averages the per-cluster readings of one second into one stable row. */
    private fun averageCpuGroups(rows: List<List<CpuGroupMetric>>): List<CpuGroupMetric> {
        if (rows.isEmpty()) return emptyList()
        val names = rows.first().map { it.name }
        return names.map { name ->
            val percent = rows.mapNotNull { row ->
                row.firstOrNull { it.name == name }?.percent?.finiteOrNull()
            }.takeIf { it.isNotEmpty() }?.average()?.toFloat()
            val freq = rows.mapNotNull { row ->
                row.firstOrNull { it.name == name }?.freqMHz
            }.filter { it > 0 }.takeIf { it.isNotEmpty() }?.average()?.toInt()
            CpuGroupMetric(name, percent, freq)
        }
    }

    @Synchronized
    fun currentLiveStats(): LiveStats {
        if (!isRecording || samples.isEmpty()) return LiveStats()
        val measuredMs = measuredDurationMs()
        val avgFps = timeWeightedAverageFps(samples) ?: 0f
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
    fun stop(targetFps: Int, shellLogs: String = ""): SessionRecord? {
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
        // actually measured, not the average of the per second readings. The sampler's
        // frame counter changes meaning between sources, so the span between readings
        // is the reliable denominator for every source.
        val avgFps = timeWeightedAverageFps(samples) ?: sortedFps.average().toFloat()
        val maxFps = if (maxFpsSeen == 0f) sortedFps.last() else maxFpsSeen
        val minFps = if (minFpsSeen == Float.MAX_VALUE) sortedFps.first() else minFpsSeen

        // The 1% and 5% lows are the worst window of the run, taken from the frame times.
        val low1 = if (low1Seen == Float.MAX_VALUE) sortedFps.first() else low1Seen
        val low5 = if (low5Seen == Float.MAX_VALUE) sortedFps.first() else low5Seen

        val totalFrames = accumulatedFrames
        val smoothness = if (totalFrames > 0) {
            ((1f - (accumulatedJank.toFloat() / totalFrames)) * 100f).coerceIn(0f, 100f)
        } else 100f
        // Scene calls this "≥45 FPS Smoothness". It is not the target hold and
        // it is not the jank-free share, so all three stay separate.
        val sceneSmoothnessPercent = if (evaluationFps.isNotEmpty()) {
            (evaluationFps.count { it >= 45 }.toFloat() * 100f /
                evaluationFps.size).finiteOrNull()?.coerceIn(0f, 100f)
        } else null

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
        val capSeries = secondBuckets.map { it.capFps }
        val thermalCapSeconds = secondBuckets.count { it.capCause == "heat" }

        val cpuGroupNames = secondBuckets.firstOrNull { it.cpuGroups.isNotEmpty() }
            ?.cpuGroups?.map { it.name }.orEmpty()
        val cpuGroupSeries = cpuGroupNames.map { name ->
            secondBuckets.map { bucket ->
                bucket.cpuGroups.firstOrNull { it.name == name }?.percent?.finiteOrNull()
            }
        }
        val cpuGroupFreqSeries = cpuGroupNames.map { name ->
            secondBuckets.map { bucket ->
                bucket.cpuGroups.firstOrNull { it.name == name }?.freqMHz?.toFloat()
            }
        }

        // One series per physical core; a device that only publishes some cores
        // simply leaves the others null instead of copying a neighbour's value.
        val coreCount = secondBuckets.maxOfOrNull { it.coreLoads.size } ?: 0
        val perCoreLoadSeries = (0 until coreCount).map { core ->
            secondBuckets.map { it.coreLoads.getOrNull(core) }
        }
        val perCoreFreqSeries = (0 until coreCount).map { core ->
            secondBuckets.map { it.coreFreqs.getOrNull(core) }
        }
        val ddrFreqSeries = secondBuckets.map { it.ddrFreqMHz }
        val threadNames = secondBuckets.firstOrNull { it.threads.isNotEmpty() }
            ?.threads?.map { it.name }.orEmpty()
        val threadAvgLoads = threadNames.map { name ->
            secondBuckets.mapNotNull { bucket ->
                bucket.threads.firstOrNull { it.name == name }?.percent?.finiteOrNull()
            }.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0f
        }
        val threadMaxLoads = threadNames.map { name ->
            secondBuckets.mapNotNull { bucket ->
                bucket.threads.firstOrNull { it.name == name }?.percent?.finiteOrNull()
            }.maxOrNull() ?: 0f
        }
        val avgCurrent = secondBuckets.mapNotNull { it.currentMa?.finiteOrNull() }
            .takeIf { it.isNotEmpty() }?.average()?.toFloat()
        val voltage = secondBuckets.firstNotNullOfOrNull { it.voltageMv }
        val cycles = secondBuckets.firstNotNullOfOrNull { it.cycles }

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
            avgPowerW = avgPower,
            capSeries = capSeries,
            capChanges = capChanges.toList(),
            thermalCapSeconds = thermalCapSeconds,
            sceneSmoothnessPercent = sceneSmoothnessPercent,
            device = deviceInfo,
            powerSeries = secondBuckets.mapNotNull { it.powerWatts?.finiteOrNull() },
            cpuFreqSeries = secondBuckets.map { it.cpuFreqMHz.toFloat() },
            gpuFreqSeries = secondBuckets.map { it.gpuFreqMHz.toFloat() },
            cpuTempSeries = secondBuckets.map { it.cpuTempC },
            socTempSeries = secondBuckets.map { it.socTempC },
            gpuPercentSeries = secondBuckets.map { it.gpuPercent },
            cpuGroupNames = cpuGroupNames,
            cpuGroupSeries = cpuGroupSeries,
            cpuGroupFreqSeries = cpuGroupFreqSeries,
            perCoreLoadSeries = perCoreLoadSeries,
            perCoreFreqSeries = perCoreFreqSeries,
            ddrFreqSeries = ddrFreqSeries,
            threadNames = threadNames,
            threadAvgLoads = threadAvgLoads,
            threadMaxLoads = threadMaxLoads,
            avgCurrentMa = avgCurrent,
            voltageMv = voltage,
            batteryCycles = cycles,
            shellLogs = shellLogs
        )

        store.add(record)
        store.addSamples(samples)
        return record
    }

    /**
     * Average of the readings over the span of time they actually cover.
     *
     * The sampler's frame counter means different things to different sources: some
     * publish the growth of a one second window and others a lifetime total. Taking
     * differences of it made a 120 fps match report 3 fps while max and min still
     * read 133 and 60. The span between readings is source independent, so each
     * reading is weighted by the time it covers and idle gaps are left out.
     */
    internal fun timeWeightedAverageFps(readings: List<SessionSample>): Float? {
        val usable = readings.filter { sample ->
            sample.fps.finiteOrNull()?.let { it > 0f } == true
        }
        if (usable.isEmpty()) return null
        if (usable.size == 1) return usable[0].fps

        var weighted = 0.0
        var spanMs = 0L
        for (i in 1 until usable.size) {
            val delta = usable[i].timestamp - usable[i - 1].timestamp
            if (delta <= 0L || delta > GAP_THRESHOLD_MS) continue
            weighted += usable[i].fps.toDouble() * delta
            spanMs += delta
        }
        return if (spanMs > 0L) {
            (weighted / spanMs).toFloat()
        } else {
            usable.map { it.fps }.average().toFloat()
        }
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
