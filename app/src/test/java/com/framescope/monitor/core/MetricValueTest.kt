package com.framescope.monitor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A number without a state is a number the user cannot trust.
 *
 * "No battery reading" and "the battery reading is from eleven seconds ago" are different
 * situations with different causes, and the interface has to be able to tell them apart.
 */
class MetricValueTest {

    private val now = 10_000_000_000L

    @Test
    fun anAvailableValueIsFreshInsideItsWindow() {
        val value = MetricValue.available(78, "BatteryManager", now)
        assertTrue(value.isAvailable)
        assertTrue(value.isFresh(now, maxAgeMs = 2_000L))
        assertEquals(0L, value.ageMs(now))
    }

    @Test
    fun aValueThatAgedPastItsWindowIsStale() {
        val value = MetricValue.available(78, "BatteryManager", now - 5_000_000_000L)
        assertFalse(value.isFresh(now, maxAgeMs = 2_000L))

        val marked = value.withFreshness(now, maxAgeMs = 2_000L)
        assertEquals(MetricAvailability.STALE, marked.availability)
        assertFalse(marked.isAvailable)
        // The value itself is kept, so the history still has it.
        assertEquals(78, marked.value)
    }

    @Test
    fun anUnavailableValueNeverBecomesFresh() {
        val value = MetricValue.unavailable<Int>(
            source = "SurfaceFlinger",
            availability = MetricAvailability.SHIZUKU_REQUIRED,
            nowElapsedNanos = now
        )
        assertFalse(value.isAvailable)
        assertFalse(value.isFresh(now, maxAgeMs = 60_000L))
        assertNull(value.value)
    }

    @Test
    fun theStatesAreDistinctSoTheUserCanBeToldWhy() {
        val states = MetricAvailability.entries.toSet()
        assertEquals(7, states.size)
        assertTrue(MetricAvailability.SHIZUKU_REQUIRED in states)
        assertTrue(MetricAvailability.PERMISSION_REQUIRED in states)
        assertTrue(MetricAvailability.UNSUPPORTED in states)
        assertTrue(MetricAvailability.WARMING_UP in states)
        assertTrue(MetricAvailability.STALE in states)
        assertTrue(MetricAvailability.ERROR in states)
    }

    @Test
    fun theAgeNeverGoesNegative() {
        val value = MetricValue.available(1, "x", now + 5_000_000_000L)
        assertEquals(0L, value.ageMs(now))
    }

    @Test
    fun systemMetricsKnowWhichCollectorProducedEachValue() {
        val metrics = SystemMetrics(
            batteryLevel = 80,
            gpuName = "Adreno",
            availability = mapOf(
                SystemMetric.BATTERY_LEVEL to MetricAvailability.AVAILABLE,
                SystemMetric.GPU_NAME to MetricAvailability.SHIZUKU_REQUIRED,
                SystemMetric.THERMAL_MAX_C to MetricAvailability.UNSUPPORTED
            ),
            sources = mapOf(
                SystemMetric.BATTERY_LEVEL to "BatteryManager",
                SystemMetric.GPU_NAME to "SurfaceFlinger"
            )
        )
        assertEquals(MetricAvailability.AVAILABLE, metrics.availabilityOf(SystemMetric.BATTERY_LEVEL))
        assertEquals(MetricAvailability.SHIZUKU_REQUIRED, metrics.availabilityOf(SystemMetric.GPU_NAME))
        assertEquals(MetricAvailability.UNSUPPORTED, metrics.availabilityOf(SystemMetric.THERMAL_MAX_C))
        assertEquals("BatteryManager", metrics.sourceOf(SystemMetric.BATTERY_LEVEL))
    }

    @Test
    fun aMetricWithAValueButNoRecordedStateCountsAsAvailable() {
        val metrics = SystemMetrics(batteryLevel = 50)
        assertEquals(MetricAvailability.AVAILABLE, metrics.availabilityOf(SystemMetric.BATTERY_LEVEL))
        assertEquals(
            MetricAvailability.UNSUPPORTED,
            metrics.availabilityOf(SystemMetric.GPU_FREQ_MHZ)
        )
    }
}
