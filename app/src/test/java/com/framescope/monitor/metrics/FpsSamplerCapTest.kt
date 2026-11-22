package com.framescope.monitor.metrics

import androidx.test.core.app.ApplicationProvider
import com.framescope.monitor.shell.ShellHub
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A compositor counter sees every surface, not only the game. The few extra frames
 * of the overlay must never be published as a frame rate the screen cannot show.
 */
@RunWith(RobolectricTestRunner::class)
class FpsSamplerCapTest {

    private fun sampler() = FpsSampler(ShellHub(ApplicationProvider.getApplicationContext()))

    @Test
    fun compositorOvershootIsHeldAtTheDisplayRefreshRate() {
        assertEquals(120f, sampler().settleToGameCap(133f, 120f), 0.01f)
        assertEquals(60f, sampler().settleToGameCap(62f, 60f), 0.01f)
    }

    @Test
    fun aReadingBelowTheCapIsNeverInflated() {
        assertEquals(90f, sampler().settleToGameCap(90f, 120f), 0.01f)
        assertEquals(60f, sampler().settleToGameCap(60f, 120f), 0.01f)
    }

    @Test
    fun aBrokenReadingIsNotTurnedIntoAPlausibleOne() {
        assertEquals(300f, sampler().settleToGameCap(300f, 120f), 0.01f)
    }
}
