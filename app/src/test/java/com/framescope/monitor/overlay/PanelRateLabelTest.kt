package com.framescope.monitor.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rate of the panel on the pill.
 *
 * The panel of this phone can do 144 frames a second and runs at 120 while
 * it is idle. The label has to say the rate the screen can do, or the number
 * next to it looks like a fault.
 */
class PanelRateLabelTest {

    @Test
    fun `the rate the panel can do is shown`() {
        assertEquals("144 Hz", panelRateLabel(144f, 144f))
    }

    @Test
    fun `a panel that runs slower than it can says both`() {
        assertEquals("120/144 Hz", panelRateLabel(144f, 120f))
    }

    @Test
    fun `a panel without a known rate shows the one it runs at`() {
        assertEquals("120 Hz", panelRateLabel(null, 120f))
    }

    @Test
    fun `nothing known shows nothing`() {
        assertEquals("", panelRateLabel(null, null))
    }
}
