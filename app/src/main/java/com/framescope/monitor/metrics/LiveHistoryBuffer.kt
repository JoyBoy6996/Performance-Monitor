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
 *
 * The readings arrive on a background thread and the dashboard reads them
 * while it draws. Every access therefore goes through one lock, and the
 * reader gets a copy that cannot change under it. Without that, the copy
 * could walk an array that was being resized, and it saw a slot that was
 * empty - a reading that was never there, and a crash on the way to the
 * screen.
 */
class LiveHistoryBuffer(private val capacity: Int = 120) {

    /** The lock every reader and every writer of the buffers takes. */
    private val lock = Any()

    private val fps = ArrayDeque<LivePoint>()
    private val frameTime = ArrayDeque<LivePoint>()
    private val cpu = ArrayDeque<LivePoint>()
    private val temperature = ArrayDeque<LivePoint>()
    private val ram = ArrayDeque<LivePoint>()
    private val gpu = ArrayDeque<LivePoint>()

    /** The frame rate and the frame time of one poll. */
    fun addFrames(timeMs: Long, fpsValue: Float, frameTimeValue: Float) = synchronized(lock) {
        if (fpsValue.isFinite() && fpsValue > 0f) push(fps, LivePoint(timeMs, fpsValue))
        if (frameTimeValue.isFinite() && frameTimeValue > 0f) {
            push(frameTime, LivePoint(timeMs, frameTimeValue))
        }
    }

    /** The load of the processor. */
    fun addCpu(timeMs: Long, percent: Float) = synchronized(lock) {
        if (percent.isFinite() && percent >= 0f) push(cpu, LivePoint(timeMs, percent))
    }

    /**
     * The readings of the device.
     *
     * A reading that is the same as the one before it is not kept again: the
     * temperature does not change every second, and a long flat line of the
     * same number says less than the moments where it really moved.
     */
    fun addDevice(timeMs: Long, temperatureC: Float?, ramPercent: Int?, gpuPercent: Float?) =
        synchronized(lock) {
            temperatureC?.takeIf { it.isFinite() && it > 0f }
                ?.let { push(temperature, LivePoint(timeMs, it)) }
            ramPercent?.takeIf { it >= 0 }?.let { push(ram, LivePoint(timeMs, it.toFloat())) }
            gpuPercent?.takeIf { it.isFinite() && it >= 0f }?.let { push(gpu, LivePoint(timeMs, it)) }
        }

    /** Forgets everything, e.g. when the measurement is switched off. */
    fun clear() = synchronized(lock) {
        fps.clear()
        frameTime.clear()
        cpu.clear()
        temperature.clear()
        ram.clear()
        gpu.clear()
    }

    /**
     * The readings as they are now.
     *
     * The copy is taken under the lock, so it is a snapshot and not a view of
     * a buffer that is being written to.
     */
    fun snapshot(): LiveHistory = synchronized(lock) {
        LiveHistory(
            fps = fps.snapshot(),
            frameTimeMs = frameTime.snapshot(),
            cpuPercent = cpu.snapshot(),
            temperatureC = temperature.snapshot(),
            ramPercent = ram.snapshot(),
            gpuPercent = gpu.snapshot()
        )
    }

    /** A copy of one buffer that cannot change while it is being read. */
    private fun ArrayDeque<LivePoint>.snapshot(): List<LivePoint> =
        toList().filterNotNull()

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
