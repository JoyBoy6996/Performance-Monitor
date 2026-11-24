package com.framescope.monitor.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The overlay screen.
 *
 * The rows are what the three wide styles show. The pill and the compact
 * meter show one number, so a chip that does nothing there must say so.
 */
class OverlayScreenTest {

    @Test
    fun `the rows do nothing in the pill and in the compact meter`() {
        assertFalse(rowsApplyToStyle("PILL"))
        assertFalse(rowsApplyToStyle("pill"))
        assertFalse(rowsApplyToStyle("COMPACT"))
    }

    @Test
    fun `the rows are the setting of the three wide styles`() {
        assertTrue(rowsApplyToStyle("CLASSIC"))
        assertTrue(rowsApplyToStyle("DETAILED"))
        assertTrue(rowsApplyToStyle("RTSS"))
    }

    @Test
    fun `an unknown style keeps the rows`() {
        assertTrue(rowsApplyToStyle("Something else"))
    }
}
