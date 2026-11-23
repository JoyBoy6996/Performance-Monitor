package com.framescope.monitor.metrics

import com.framescope.monitor.core.LiveHistory
import com.framescope.monitor.core.LivePoint

/**
 * A short memory of the live readings, for the graphs of the dashboard.
 *
 * The buffer keeps the last readings of every metric and forgets the rest. The
 * reading of the frame rate comes every few hundred milliseconds and the
 * reading of the temperature every few seconds, so each metric keeps its own
 * moments: a graph that lined them up would show a temperature that was never
 * measured at that moment.
 */
class LiveHistoryBuffer(private val capacity: Int = 120) {

    private val fps = ArrayDeque<LivePoint>()
    private val frameTime = ArrayDeque<LivePoint>()
    private val cpu = ArrayDeque<LivePoint>()
    private val temperature = ArrayDeque<LivePoint>()
    private val ram = ArrayDeque<LivePoint>()
    private val gpu = ArrayDeque<LivePoint>()

    /** The frame rate and the frame time of one poll. */
    fun addFrames(timeMs: Long, fpsValue: Float, frameTimeValue: Float) {
        if (fpsValue.isFinite() && fpsValue > 0f) push(fps, LivePoint(timeMs, fpsValue))
        if (frameTimeValue.isFinite() && frameTimeValue > 0f) {
            push(frameTime, LivePoint(timeMs, frameTimeValue))
        }
    }

    /** The load of the processor. */
    fun addCpu(timeMs: Long, percent: Float) {
        if (percent.isFinite() && percent >= 0f) push(cpu, LivePoint(timeMs, percent))
    }

    /**
     * The readings of the device.
     *
     * A reading that is the same as the one before it is not kept again: the
     * temperature does not change every second, and a long flat line of the
     * same number says less than the moments where it really moved.
     */
    fun addDevice(timeMs: Long, temperatureC: Float?, ramPercent: Int?, gpuPercent: Float?) {
        temperatureC?.takeIf { it.isFinite() && it > 0f }?.let { push(temperature, LivePoint(timeMs, it)) }
        ramPercent?.takeIf { it >= 0 }?.let { push(ram, LivePoint(timeMs, it.toFloat())) }
        gpuPercent?.takeIf { it.isFinite() && it >= 0f }?.let { push(gpu, LivePoint(timeMs, it)) }
    }

    /** Forgets everything, e.g. when the measurement is switched off. */
    fun clear() {
        fps.clear()
        frameTime.clear()
        cpu.clear()
        temperature.clear()
        ram.clear()
        gpu.clear()
    }

    /** The readings as they are now. */
    fun snapshot(): LiveHistory = LiveHistory(
        fps = fps.toList(),
        frameTimeMs = frameTime.toList(),
        cpuPercent = cpu.toList(),
        temperatureC = temperature.toList(),
        ramPercent = ram.toList(),
        gpuPercent = gpu.toList()
    )

    private fun push(buffer: ArrayDeque<LivePoint>, point: LivePoint) {
        val last = buffer.lastOrNull()
        if (last != null && last.value == point.value && point.timeMs - last.timeMs < 1_000L) {
            // The same value in the same second is not a new reading.
            return
        }
        buffer.addLast(point)
        while (buffer.size > capacity) buffer.removeFirst()
    }
}
