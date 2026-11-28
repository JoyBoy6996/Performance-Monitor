package com.framescope.monitor.metrics

import androidx.test.core.app.ApplicationProvider
import com.framescope.monitor.shell.ShellHub
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * How a reading is published.
 *
 * A game is held to the caps it runs at, because the compositor adds a few
 * frames on top of the game's own rate. An ordinary app is not: it is shown
 * exactly what it presented, so 138 stays 138 and 125 stays 125.
 */
@RunWith(RobolectricTestRunner::class)
class ReadingSettleTest {

    private fun sampler() = FpsSampler(ShellHub(ApplicationProvider.getApplicationContext()))

    @Test
    fun anAppShowsExactlyWhatItPresented() {
        assertEquals(138f, sampler().settleReading(138f, 144f, isGame = false), 0.01f)
        assertEquals(125f, sampler().settleReading(125f, 144f, isGame = false), 0.01f)
        assertEquals(119f, sampler().settleReading(119f, 144f, isGame = false), 0.01f)
    }

    @Test
    fun anAppIsNeverShownMoreThanThePanelCanPresent() {
        assertEquals(144f, sampler().settleReading(150f, 144f, isGame = false), 0.01f)
        assertEquals(120f, sampler().settleReading(130f, 120f, isGame = false), 0.01f)
    }

    @Test
    fun aGameIsStillHeldToItsOwnCap() {
        assertEquals(120f, sampler().settleReading(129f, 144f, isGame = true), 0.01f)
        assertEquals(90f, sampler().settleReading(93f, 144f, isGame = true), 0.01f)
    }

    @Test
    fun aGameBelowItsCapIsNotInflated() {
        assertEquals(110f, sampler().settleReading(110f, 144f, isGame = true), 0.01f)
        assertEquals(115f, sampler().settleReading(115f, 144f, isGame = true), 0.01f)
    }
}
