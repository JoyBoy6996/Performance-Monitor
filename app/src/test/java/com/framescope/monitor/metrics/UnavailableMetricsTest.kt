package com.framescope.monitor.metrics

import com.framescope.monitor.core.MetricAvailability
import com.framescope.monitor.core.SystemMetric
import com.framescope.monitor.shell.ShellHub
import com.framescope.monitor.shell.ShizukuRunner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Without a shell there is still a snapshot.
 *
 * The old sampler returned null and the interface had nothing to show. Now every metric
 * carries its own state, so the interface can say "this needs Shizuku" for the readings
 * that do and show the rest.
 */
@RunWith(RobolectricTestRunner::class)
class UnavailableMetricsTest {

    @Test
    fun noShizukuResultStillDescribesEveryPrivilegedMetric() = runBlocking {
        val sampler = SystemSampler(ShellHub(ShizukuRunner(context = null)))

        val metrics = sampler.sample()

        // Nothing could be read, and every reading says so.
        assertNull(metrics.cpuPercent)
        assertNull(metrics.gpuName)
        assertNull(metrics.thermalMaxC)
        assertEquals(
            MetricAvailability.SHIZUKU_REQUIRED,
            metrics.availabilityOf(SystemMetric.CPU_PERCENT)
        )
        assertEquals(
            MetricAvailability.SHIZUKU_REQUIRED,
            metrics.availabilityOf(SystemMetric.GPU_NAME)
        )
        assertEquals(
            MetricAvailability.SHIZUKU_REQUIRED,
            metrics.availabilityOf(SystemMetric.THERMAL_MAX_C)
        )
    }

    @Test
    fun aFailedBatchDoesNotThrowTheOtherMetricsAway() = runBlocking {
        val sampler = SystemSampler(ShellHub(ShizukuRunner(context = null)))

        // The first reading cannot say how busy the CPU is, because that needs two of
        // them, but the snapshot still exists.
        val metrics = sampler.sample()
        assertNull(metrics.cpuPercent)
        assertEquals(
            MetricAvailability.SHIZUKU_REQUIRED,
            metrics.availabilityOf(SystemMetric.CPU_PERCENT)
        )
    }
}
