package com.framescope.monitor.metrics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A frame rate that no screen can show is not a frame rate.
 *
 * The phone this app runs on has a 144 Hz panel. A reading of 298 frames per
 * second came from a timestamp column that was not the frame time at all, and
 * it was shown as if it were real. Every source has to pass this check before
 * its number is published.
 */
class PlausibleFpsTest {

    @Test
    fun aRateTheScreenCannotShowIsRefused() {
        assertFalse(isPlausibleFps(298.2f, 144f))
        assertFalse(isPlausibleFps(174f, 144f))
    }

    @Test
    fun aRateTheScreenCanShowIsKept() {
        assertTrue(isPlausibleFps(90f, 144f))
        assertTrue(isPlausibleFps(120f, 144f))
        assertTrue(isPlausibleFps(144f, 144f))
    }

    @Test
    fun aSmallMarginAboveTheRefreshRateIsAllowed() {
        // The panel reports 144 Hz; a game can land a little above it.
        assertTrue(isPlausibleFps(148f, 144f))
        assertFalse(isPlausibleFps(160f, 144f))
    }

    @Test
    fun aRateWithoutAKnownRefreshRateIsJudgedOnItsOwn() {
        assertFalse(isPlausibleFps(0f, null))
        assertFalse(isPlausibleFps(-5f, null))
        assertTrue(isPlausibleFps(60f, null))
        assertFalse(isPlausibleFps(300f, null))
    }
}
