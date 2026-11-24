package com.framescope.monitor.ui.screens

import com.framescope.monitor.ui.components.ChartPoint
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live graphs of the dashboard.
 *
 * A graph follows its readings until it is locked, and a locked graph keeps
 * the scale it was locked at.
 */
class MonitorLiveTest {

    @Test
    fun `the locked scale sits above the highest reading`() {
        val points = listOf(
            ChartPoint(0L, 42f),
            ChartPoint(1L, 58f),
            ChartPoint(2L, 51f)
        )

        val ceiling = graphCeiling(points, target = null)

        assertTrue("the scale must sit above the highest reading", ceiling >= 58f)
    }

    @Test
    fun `the locked scale never sits below the target`() {
        val points = listOf(ChartPoint(0L, 10f), ChartPoint(1L, 12f))

        assertTrue(graphCeiling(points, target = 45f) >= 45f)
    }

    @Test
    fun `a graph without readings still has a scale`() {
        assertTrue(graphCeiling(emptyList(), target = null) > 0f)
    }
}
