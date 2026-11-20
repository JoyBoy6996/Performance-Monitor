package com.framescope.monitor.metrics

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.view.Display
import com.framescope.monitor.core.MetricAvailability
import com.framescope.monitor.core.MetricValue

/**
 * The device metrics Android can answer on its own.
 *
 * None of these need a privileged shell, which is the point: a phone without Shizuku
 * still knows how much battery it has, how much memory it has left and how fast its
 * screen runs. Making the whole interface wait for a privileged connection was throwing
 * away information the system already had.
 *
 * Every collector reports its own state, so one of them failing never hides the others.
 */
class NativeCollectors(private val context: Context) {

    /** How much memory the system has, in bytes. */
    data class MemoryReading(
        val totalBytes: Long,
        val availableBytes: Long,
        val lowMemory: Boolean
    ) {
        val usedBytes: Long get() = (totalBytes - availableBytes).coerceAtLeast(0L)
    }

    /** The battery, as the system reports it. */
    data class BatteryReading(
        val levelPercent: Int?,
        val temperatureC: Float?,
        val currentMicroAmps: Int?,
        val voltageMilliVolts: Int?,
        val charging: Boolean,
        /** BatteryManager.EXTRA_STATUS, when the sticky broadcast reports it. */
        val status: Int? = null,
        /** BatteryManager.EXTRA_HEALTH, when the sticky broadcast reports it. */
        val health: Int? = null,
        /** BatteryManager.EXTRA_TECHNOLOGY, when the sticky broadcast reports it. */
        val technology: String? = null,
        /** BatteryManager.EXTRA_CYCLE_COUNT (API 34+), when the platform reports it. */
        val cycleCount: Int? = null
    ) {
        /**
         * The power the battery is moving, in watts.
         *
         * The current is reported as a signed value: negative while the battery is being
         * charged. The magnitude is the number a user cares about, and it is only
         * calculated when both halves of it are actually known.
         */
        val watts: Float?
            get() {
                val current = currentMicroAmps ?: return null
                val voltage = voltageMilliVolts ?: return null
                if (current == 0 || voltage <= 0) return null
                return (kotlin.math.abs(current).toDouble() * voltage / 1_000_000_000.0).toFloat()
            }
    }

    /** The battery level, the temperature and the electrical state in one reading. */
    fun battery(): MetricValue<BatteryReading> = parseBattery(
        intent = batteryIntent(),
        capacityPercent = batteryProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
        currentMicroAmps = batteryProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
        nowElapsedNanos = System.nanoTime()
    )

    /** The memory the system has. */
    fun memory(): MetricValue<MemoryReading> {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return MetricValue.unavailable<MemoryReading>(
                source = "ActivityManager",
                availability = MetricAvailability.UNSUPPORTED,
                nowElapsedNanos = System.nanoTime(),
                error = "No activity manager"
            )

        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        if (info.totalMem <= 0L) {
            return MetricValue.unavailable<MemoryReading>(
                source = "ActivityManager",
                availability = MetricAvailability.UNSUPPORTED,
                nowElapsedNanos = System.nanoTime(),
                error = "The system reported no memory"
            )
        }
        return MetricValue.available(
            MemoryReading(info.totalMem, info.availMem, info.lowMemory),
            "ActivityManager",
            System.nanoTime()
        )
    }

    /**
     * The refresh rate of the display that is actually on.
     *
     * This is the mode the screen is running in, not the fastest mode the panel supports:
     * a phone that can do 120 Hz but is set to 60 Hz is running at 60 Hz.
     */
    fun refreshRate(): MetricValue<Float> {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            ?: return MetricValue.unavailable<Float>(
                source = "DisplayManager",
                availability = MetricAvailability.UNSUPPORTED,
                nowElapsedNanos = System.nanoTime(),
                error = "No display manager"
            )

        val display = manager.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return MetricValue.unavailable<Float>(
                source = "DisplayManager",
                availability = MetricAvailability.UNSUPPORTED,
                nowElapsedNanos = System.nanoTime(),
                error = "No display"
            )

        val rate = display.mode?.refreshRate
        if (rate == null || rate <= 0f) {
            return MetricValue.unavailable<Float>(
                source = "DisplayManager",
                availability = MetricAvailability.UNSUPPORTED,
                nowElapsedNanos = System.nanoTime(),
                error = "The display reported no refresh rate"
            )
        }
        return MetricValue.available(rate, "DisplayManager", System.nanoTime())
    }

    /**
     * How hot the device thinks it is, as a severity.
     *
     * This is the thermal status - none, light, moderate, severe - and not a temperature
     * in degrees. A device that does not report it says so instead of guessing.
     */
    fun thermalStatus(): MetricValue<Int> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return MetricValue.unavailable<Int>(
                source = "PowerManager",
                availability = MetricAvailability.UNSUPPORTED,
                nowElapsedNanos = System.nanoTime(),
                error = "Thermal status needs Android 10 or newer"
            )
        }
        val manager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return MetricValue.unavailable<Int>(
                source = "PowerManager",
                availability = MetricAvailability.UNSUPPORTED,
                nowElapsedNanos = System.nanoTime(),
                error = "No power manager"
            )
        val status = manager.getCurrentThermalStatus()
        return MetricValue.available(status, "PowerManager", System.nanoTime())
    }

    /** The sticky battery broadcast, which every device answers. */
    private fun batteryIntent(): Intent? = try {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    } catch (e: Exception) {
        null
    }

    /**
     * One of the battery manager's properties.
     *
     * The current and the voltage are not part of the battery intent, they are
     * properties of the battery manager, and a device that does not have them reports
     * that instead of reporting zero.
     */
    private fun batteryProperty(id: Int): Int? {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return null
        val value = runCatching { manager.getIntProperty(id) }.getOrNull() ?: return null
        // The manager answers with Int.MIN_VALUE when it does not know.
        if (value == Int.MIN_VALUE || value == 0) return null
        return value
    }

    companion object {

        /**
         * Reads the battery out of what the system reported.
         *
         * The level comes from the battery manager when it knows it, and from the
         * sticky broadcast otherwise. The temperature only ever comes from the broadcast,
         * and it is reported in tenths of a degree.
         */
        fun parseBattery(
            intent: Intent?,
            capacityPercent: Int? = null,
            currentMicroAmps: Int? = null,
            nowElapsedNanos: Long = System.nanoTime()
        ): MetricValue<BatteryReading> {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val fromIntent = if (level >= 0 && scale > 0) (level * 100f / scale).toInt() else null

            val percent = capacityPercent ?: fromIntent

            val rawTemperature =
                intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                    ?: Int.MIN_VALUE
            val temperature = if (rawTemperature != Int.MIN_VALUE && rawTemperature != 0) {
                rawTemperature / 10f
            } else {
                null
            }

            val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE && it > 0 }

            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                ?.takeIf { it >= 0 }
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            val health = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
                ?.takeIf { it >= 0 }
            val technology = intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)
                ?.takeIf { it.isNotBlank() }
            // Android 14 added a public cycle count. Older versions have no public
            // API for it, so the Tools screen falls back to a guarded sysfs read.
            val cycleCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                intent?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)
                    ?.takeIf { it in 0..10_000 }
            } else null

            if (percent == null && temperature == null) {
                return MetricValue.unavailable(
                    source = "BatteryManager",
                    availability = MetricAvailability.UNSUPPORTED,
                    nowElapsedNanos = nowElapsedNanos,
                    error = "The system reported neither a battery level nor a temperature"
                )
            }

            return MetricValue.available(
                BatteryReading(
                    levelPercent = percent,
                    temperatureC = temperature,
                    currentMicroAmps = currentMicroAmps,
                    voltageMilliVolts = voltage,
                    charging = charging,
                    status = status,
                    health = health,
                    technology = technology,
                    cycleCount = cycleCount
                ),
                "BatteryManager",
                nowElapsedNanos
            )
        }
    }
}
