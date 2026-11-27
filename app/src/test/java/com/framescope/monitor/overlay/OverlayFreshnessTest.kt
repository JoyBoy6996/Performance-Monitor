package com.framescope.monitor.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pill and the age of its reading.
 *
 * A rate is the growth between two counters, and each counter costs the
 * compositor a second and a half on a phone with many layers. A reading is
 * therefore a little under three seconds old by the time it reaches the pill,
 * and that is the freshest it can be. Calling every one of them out of date
 * put the word "old" on the pill over and over.
 */
class OverlayFreshnessTest {

    @Test
    fun `a reading of this device is not called out of date`() {
        assertFalse(OverlayFreshness.isStale(2_646L))
        assertFalse(OverlayFreshness.isStale(3_395L))
    }

    @Test
    fun `a reading that is really behind is called out of date`() {
        assertTrue(OverlayFreshness.isStale(6_000L))
        assertTrue(OverlayFreshness.isStale(OverlayFreshness.STALE_AFTER_MS + 1L))
    }

    @Test
    fun `the line is four seconds`() {
        assertFalse(OverlayFreshness.isStale(4_000L))
        assertTrue(OverlayFreshness.isStale(4_001L))
    }
}
