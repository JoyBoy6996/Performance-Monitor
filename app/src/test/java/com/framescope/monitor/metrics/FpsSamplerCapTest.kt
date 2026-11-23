package com.framescope.monitor.metrics

import androidx.test.core.app.ApplicationProvider
import com.framescope.monitor.shell.ShellHub
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A compositor counter sees every surface, not only the game. The few extra frames
 * of the overlay must never be published as a frame rate the screen cannot show -
 * and a rate the screen can show must never be pulled down to the next "game rate",
 * because a number the device never measured is worse than an honest one.
 */
@RunWith(RobolectricTestRunner::class)
class FpsSamplerCapTest {

    private fun sampler() = FpsSampler(ShellHub(ApplicationProvider.getApplicationContext()))

    @Test
    fun compositorOvershootIsHeldAtTheDisplayRefreshRate() {
        assertEquals(120f, sampler().capAtDisplayRate(133f, 120f), 0.01f)
        assertEquals(60f, sampler().capAtDisplayRate(62f, 60f), 0.01f)
    }

    @Test
    fun aRomThatReportsNoRefreshRateChangesNothing() {
        // Without a known rate of the screen there is no ceiling to hold the
        // reading at, so the measurement is published as it is.
        assertEquals(133f, sampler().capAtDisplayRate(133f, null), 0.01f)
        assertEquals(128f, sampler().capAtDisplayRate(128f, null), 0.01f)
    }

    @Test
    fun aGenuineHighRefreshReadingIsNotPulledDown() {
        assertEquals(144f, sampler().capAtDisplayRate(144f, null), 0.01f)
        assertEquals(144f, sampler().capAtDisplayRate(144f, 144f), 0.01f)
    }

    @Test
    fun a144HzPanelKeepsAReadingItCanReallyShow() {
        // 133 is not one of the "game rates", but a 144 Hz screen really shows
        // 133 frames a second. Pulling it down to 120 would publish a number
        // the device never measured.
        assertEquals(133f, sampler().capAtDisplayRate(133f, 144f), 0.01f)
        assertEquals(144f, sampler().capAtDisplayRate(144f, 144f), 0.01f)
    }

    @Test
    fun aReadingThatIsNotAGameRateIsPublishedAsMeasured() {
        assertEquals(91f, sampler().capAtDisplayRate(91f, 120f), 0.01f)
        assertEquals(129f, sampler().capAtDisplayRate(129f, 144f), 0.01f)
    }

    @Test
    fun aReadingBelowTheCapIsNeverInflated() {
        assertEquals(90f, sampler().capAtDisplayRate(90f, 120f), 0.01f)
        assertEquals(60f, sampler().capAtDisplayRate(60f, 120f), 0.01f)
    }

    @Test
    fun aBrokenReadingIsNotTurnedIntoAPlausibleOne() {
        assertEquals(300f, sampler().capAtDisplayRate(300f, 120f), 0.01f)
    }
}
