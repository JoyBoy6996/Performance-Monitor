package com.framescope.monitor.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The panel rate is a physical fact of the screen; the game's frame rate is not.
 * The first line of a SurfaceFlinger latency dump carries that fact exactly.
 */
class DisplayRefreshTest {

    @Test
    fun readsThePanelRateFromTheLatencyHeader() {
        assertEquals(144f, refreshHzFromLatencyDump("6944444\n0 0 0\n0 0 0"), 0.05f)
        assertEquals(120f, refreshHzFromLatencyDump("8333333\n"), 0.05f)
        assertEquals(60f, refreshHzFromLatencyDump("16666666\n"), 0.05f)
    }

    @Test
    fun aMissingOrBrokenPeriodIsNotGuessed() {
        assertNull(refreshHzFromLatencyDump(""))
        assertNull(refreshHzFromLatencyDump("0\n"))
        assertNull(refreshHzFromLatencyDump("999999999\n"))
        assertNull(refreshHzFromLatencyDump("garbage\n"))
    }
}
