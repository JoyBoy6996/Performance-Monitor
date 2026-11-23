package com.framescope.monitor.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of the chart system that can be checked without a screen.
 *
 * The rule of the whole app is that a number on a graph is a number that was
 * really measured, at the moment it was measured. These tests hold that line.
 */
class ChartCoreTest {

    private fun points(vararg pairs: Pair<Long, Float>): List<ChartPoint> =
        pairs.map { ChartPoint(it.first, it.second) }

    @Test
    fun theNearestSampleIsTheOneThatWasMeasured() {
        val samples = points(0L to 10f, 1_000L to 20f, 2_000L to 30f)

        val picked = nearestPoint(samples, 1_100L)

        assertNotNull(picked)
        assertEquals(1_000L, picked!!.timeMs)
        assertEquals(20f, picked.value, 0.001f)
    }

    @Test
    fun theNearestSampleIsNeverInvented() {
        val samples = points(0L to 10f, 1_000L to 20f)

        // Before the first and after the last sample the nearest real one wins.
        assertEquals(0L, nearestPoint(samples, -5_000L)?.timeMs)
        assertEquals(1_000L, nearestPoint(samples, 99_000L)?.timeMs)
    }

    @Test
    fun anEmptySeriesHasNoNearestSample() {
        assertNull(nearestPoint(emptyList(), 1_000L))
    }

    @Test
    fun theScaleCoversEveryValueAndTheTarget() {
        val scale = buildChartScale(listOf(50f, 60f, 120f), target = 90f)

        assertTrue("min was ${scale.min}", scale.min <= 50f)
        assertTrue("max was ${scale.max}", scale.max >= 120f)
        assertTrue("target was ${scale.max}", scale.max >= 90f)
        // The lowest reading is at the bottom and the highest at the top.
        assertEquals(0f, scale.fraction(scale.min), 0.0001f)
        assertEquals(1f, scale.fraction(scale.max), 0.0001f)
    }

    @Test
    fun aPinnedEdgeStaysWhereItWasPinned() {
        val scale = buildChartScale(listOf(50f, 60f), pinnedMin = 0f)

        // A frame rate does not go below zero, so the scale does not either.
        assertEquals(0f, scale.min, 0.0001f)
        assertTrue("max was ${scale.max}", scale.max >= 60f)
    }

    @Test
    fun aScaleOfOneValueStillHasRoom() {
        val scale = buildChartScale(listOf(60f))

        assertTrue("min was ${scale.min}", scale.min < 60f)
        assertTrue("max was ${scale.max}", scale.max > 60f)
        assertEquals(0.5f, scale.fraction(60f), 0.0001f)
    }

    @Test
    fun theTicksAreSpreadOverTheRealLengthOfTheRun() {
        val ticks = buildTimeTicks(0L, 60_000L, tickCount = 5)

        assertEquals(5, ticks.size)
        assertEquals(0L, ticks.first())
        assertEquals(60_000L, ticks.last())
    }

    @Test
    fun theTicksOfARunThatLastedOneSecondAreOneTick() {
        val ticks = buildTimeTicks(1_000L, 1_000L)

        assertEquals(listOf(1_000L), ticks)
    }

    @Test
    fun aMomentIsPrintedAsMinutesAndSeconds() {
        assertEquals("0:05", formatElapsed(5_000L))
        assertEquals("1:00", formatElapsed(60_000L))
        assertEquals("12:34", formatElapsed(754_000L))
        assertEquals("1:02:03", formatElapsed(3_723_000L))
    }

    @Test
    fun theRangesOfARunOnlyOfferWhatFits() {
        val short = ChartRange.availableFor(30_000L)
        assertEquals(listOf(ChartRange.FULL), short)

        val long = ChartRange.availableFor(1_800_000L)
        assertTrue(long.contains(ChartRange.FIVE_MINUTES))
        assertTrue(long.contains(ChartRange.FULL))
        // A range longer than the run is not offered.
        assertTrue(!long.contains(ChartRange.THIRTY_MINUTES))
    }

    @Test
    fun aLongRunIsDrawnWithFewerPointsAndKeepsItsSpikes() {
        val samples = (0 until 4_000).map { index ->
            val value = if (index == 2_000) 5f else 60f
            ChartPoint(index * 1_000L, value)
        }

        val drawn = downsamplePreservingSpikes(samples, 200)

        assertTrue("was ${drawn.size}", drawn.size <= 220)
        // The spike is still there, at the moment it happened.
        assertTrue(drawn.any { it.value == 5f })
        assertEquals(0L, drawn.first().timeMs)
        assertEquals(samples.last().timeMs, drawn.last().timeMs)
    }

    @Test
    fun aShortSeriesIsNotTouched() {
        val samples = points(0L to 1f, 1_000L to 2f)

        assertEquals(samples, downsamplePreservingSpikes(samples, 100))
    }

    @Test
    fun aRunThatKeepsItsMomentsIsDrawnOnItsOwnClock() {
        val times = listOf(0L, 4_000L, 9_000L)
        val values = listOf(60f, 30f, 45f)

        val plotted = chartPoints(values, times)

        assertEquals(3, plotted.size)
        assertEquals(4_000L, plotted[1].timeMs)
        assertEquals(30f, plotted[1].value, 0.001f)
    }

    @Test
    fun anOldRunFallsBackToOneSecondPerSample() {
        val plotted = chartPoints(listOf(60f, 55f, 50f), emptyList())

        assertEquals(3, plotted.size)
        assertEquals(0L, plotted[0].timeMs)
        assertEquals(1_000L, plotted[1].timeMs)
        assertEquals(2_000L, plotted[2].timeMs)
        assertTrue(!hasRealTimes(emptyList(), listOf(60f, 55f, 50f)))
    }

    @Test
    fun aReadingThatIsNotANumberIsLeftOut() {
        val plotted = chartPoints(listOf(60f, Float.NaN, 50f), listOf(0L, 1_000L, 2_000L))

        assertEquals(2, plotted.size)
        assertEquals(60f, plotted[0].value, 0.001f)
        assertEquals(50f, plotted[1].value, 0.001f)
    }

    @Test
    fun aWindowKeepsTheSamplesThatAreInsideIt() {
        val samples = points(0L to 1f, 1_000L to 2f, 2_000L to 3f)

        val inside = samples.withinWindow(1_000L, 2_000L)

        assertEquals(2, inside.size)
        assertEquals(1_000L, inside.first().timeMs)
    }

    @Test
    fun aSeriesKeepsItsOwnUnitAndColour() {
        val line = ChartSeries(
            label = "FPS",
            unit = "fps",
            color = Color(0xFF55D6A1),
            points = points(0L to 60f, 1_000L to 58f),
            target = 45f
        )

        assertEquals("FPS", line.label)
        assertEquals("fps", line.unit)
        assertEquals(45f, line.target!!, 0.001f)
    }
}
