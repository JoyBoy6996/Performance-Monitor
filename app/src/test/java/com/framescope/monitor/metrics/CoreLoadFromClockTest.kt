package com.framescope.monitor.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The load of a core, read from the clock of that core.
 *
 * The load is the place of the clock between the two ends of that clock. It
 * is a real reading of the hardware, and without the two ends there is no
 * reading at all.
 */
class CoreLoadFromClockTest {

    @Test
    fun `a core at its highest clock is fully busy`() {
        val load = coreLoadFromClock(khz = 2_400_000L, minKhz = 300_000L, maxKhz = 2_400_000L)

        assertEquals(100f, load!!, 0.1f)
    }

    @Test
    fun `a core at its lowest clock is idle`() {
        val load = coreLoadFromClock(khz = 300_000L, minKhz = 300_000L, maxKhz = 2_400_000L)

        assertEquals(2f, load!!, 0.1f)
    }

    @Test
    fun `a core in the middle is in the middle`() {
        val load = coreLoadFromClock(khz = 1_350_000L, minKhz = 300_000L, maxKhz = 2_400_000L)

        assertEquals(50f, load!!, 1f)
    }

    @Test
    fun `a clock below the lowest one is still idle`() {
        val load = coreLoadFromClock(khz = 100_000L, minKhz = 300_000L, maxKhz = 2_400_000L)

        assertEquals(2f, load!!, 0.1f)
    }

    @Test
    fun `without the ends of the clock there is no reading`() {
        assertNull(coreLoadFromClock(khz = 1_200_000L, minKhz = null, maxKhz = 2_400_000L))
        assertNull(coreLoadFromClock(khz = 1_200_000L, minKhz = 300_000L, maxKhz = null))
        assertNull(coreLoadFromClock(khz = null, minKhz = 300_000L, maxKhz = 2_400_000L))
    }

    @Test
    fun `a clock that does not have a range has no reading`() {
        // The two ends are the same: there is no place between them.
        assertNull(coreLoadFromClock(khz = 1_200_000L, minKhz = 1_200_000L, maxKhz = 1_200_000L))
    }

    @Test
    fun `the load never leaves the scale of a percent`() {
        val load = coreLoadFromClock(khz = 9_999_999L, minKhz = 300_000L, maxKhz = 2_400_000L)

        assertTrue("the load is $load", load != null && load <= 100f)
    }
}
