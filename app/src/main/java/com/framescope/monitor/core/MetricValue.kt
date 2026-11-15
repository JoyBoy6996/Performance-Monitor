package com.framescope.monitor.core

/**
 * Why a metric can or cannot be shown.
 *
 * A missing value is not the same thing as a broken one, and the interface has to say
 * which it is: "this phone does not report it" and "Shizuku is not connected" lead to
 * completely different actions from the user.
 */
enum class MetricAvailability {
    /** The value was measured and is within its freshness window. */
    AVAILABLE,

    /** A permission the user can grant would make this metric available. */
    PERMISSION_REQUIRED,

    /** Only a privileged shell can read this metric. */
    SHIZUKU_REQUIRED,

    /** The device or Android version does not expose the metric at all. */
    UNSUPPORTED,

    /**
     * The source works and has been found, but it has not produced a reading yet.
     *
     * This is not the same as being unavailable: the measurement is about to arrive.
     */
    WARMING_UP,

    /** The value was measured but is too old to present as live. */
    STALE,

    /** The collector ran and failed. */
    ERROR
}

/**
 * One measured value with everything needed to judge whether it can be trusted.
 *
 * @param value the measurement itself, null when there is nothing to show
 * @param source where the number came from, in words a user can act on
 * @param measuredAtElapsedNanos when the number was taken, on the monotonic clock
 * @param availability whether the number can be shown and why not when it cannot
 * @param error the failure, in plain words, when there was one
 */
data class MetricValue<T>(
    val value: T?,
    val source: String,
    val measuredAtElapsedNanos: Long,
    val availability: MetricAvailability,
    val error: String? = null
) {
    /** True only when there is a value and it is current. */
    val isAvailable: Boolean
        get() = value != null && availability == MetricAvailability.AVAILABLE

    /** How long ago the value was measured, in nanoseconds on the monotonic clock. */
    fun ageNanos(nowElapsedNanos: Long): Long =
        (nowElapsedNanos - measuredAtElapsedNanos).coerceAtLeast(0L)

    /** How long ago the value was measured, in milliseconds. */
    fun ageMs(nowElapsedNanos: Long): Long = ageNanos(nowElapsedNanos) / 1_000_000L

    /**
     * True when the value is still current enough to present as live.
     *
     * A metric that is older than its own freshness window is not a live reading, no
     * matter how good the number looks.
     */
    fun isFresh(nowElapsedNanos: Long, maxAgeMs: Long): Boolean {
        if (availability != MetricAvailability.AVAILABLE) return false
        if (value == null) return false
        return ageMs(nowElapsedNanos) <= maxAgeMs
    }

    /** The same reading, but marked as stale when it has aged past its window. */
    fun withFreshness(nowElapsedNanos: Long, maxAgeMs: Long): MetricValue<T> =
        if (availability == MetricAvailability.AVAILABLE && ageMs(nowElapsedNanos) > maxAgeMs) {
            copy(availability = MetricAvailability.STALE)
        } else {
            this
        }

    companion object {
        /** A reading that could not be taken, with the reason why. */
        fun <T> unavailable(
            source: String,
            availability: MetricAvailability,
            nowElapsedNanos: Long,
            error: String? = null
        ): MetricValue<T> = MetricValue(
            value = null,
            source = source,
            measuredAtElapsedNanos = nowElapsedNanos,
            availability = availability,
            error = error
        )

        /** A reading that was taken successfully. */
        fun <T> available(
            value: T,
            source: String,
            nowElapsedNanos: Long
        ): MetricValue<T> = MetricValue(
            value = value,
            source = source,
            measuredAtElapsedNanos = nowElapsedNanos,
            availability = MetricAvailability.AVAILABLE
        )
    }
}

/** The device metrics the app can show, each with its own collector. */
enum class SystemMetric {
    CPU_PERCENT,
    CPU_FREQ_MHZ,
    GPU_NAME,
    GPU_FREQ_MHZ,
    BATTERY_LEVEL,
    BATTERY_TEMP_C,
    POWER_WATTS,
    RAM_USED_GB,
    RAM_TOTAL_GB,
    RAM_PERCENT,
    THERMAL_MAX_C,
    REFRESH_HZ
}
