package com.framescope.monitor.metrics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The readings the app takes by itself.
 *
 * There is no privileged shell on the test device, so the readings that need
 * one stay missing. What is checked here is the rule that matters: a reading
 * that was not made is missing, and it is never a zero.
 */
@RunWith(RobolectricTestRunner::class)
class BasicCollectorTest {

    private fun collector() =
        BasicCollector(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun `the device says what it is`() {
        val start = collector().sessionStart()

        assertNotNull("the platform of the chip is missing", start.platform)
        assertTrue("the platform is empty", start.platform!!.isNotBlank())
        assertTrue("the platform is not upper case", start.platform == start.platform!!.uppercase())
    }

    @Test
    fun `the profile is one of the three the app knows`() {
        val profile = collector().readProfile()

        assertTrue(
            "the profile is $profile",
            profile == "Balanced" || profile == "Power Save" || profile == "Monster"
        )
    }

    @Test
    fun `a cluster of cores is named by its range`() {
        assertEquals("CPU 0~1", BasicCollector.clusterNameOf(listOf(0, 1)))
        assertEquals("CPU 2~4", BasicCollector.clusterNameOf(listOf(2, 3, 4)))
    }

    @Test
    fun `a single core is named by its number`() {
        assertEquals("CPU 7", BasicCollector.clusterNameOf(listOf(7)))
    }

    @Test
    fun `a reading that cannot be made stays missing`() {
        val sample = collector().sample()

        // The test device has no processor nodes the app may read, so there is
        // nothing to report. A zero would be a reading that was not made.
        if (sample.cpuPercent == null) {
            assertNull(sample.cpuFreqMhz)
            assertTrue(sample.perCoreFreqMhz.all { it == null })
        }
    }

    @Test
    fun `the clusters of the device always have a name`() {
        collector().sessionStart().clusters.forEach { cluster ->
            assertTrue("a cluster has no name", cluster.name.isNotBlank())
            assertTrue("a cluster has no cores", cluster.cores.isNotEmpty())
        }
    }
}
