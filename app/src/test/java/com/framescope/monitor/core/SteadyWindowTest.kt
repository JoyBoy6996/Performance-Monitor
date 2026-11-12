package com.framescope.monitor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A game often runs at a lower rate before the action starts - a lobby at 90
 * fps and the fight at 120. Those slower seconds are the chosen rate, not a
 * dropped frame, so the statistics only judge the part of the run where the
 * app was at its top rate.
 */
class SteadyWindowTest {

    @Test
    fun ignoresTheLobbyWhenTheActionStarts() {
        val series = List(10) { 90f } + List(20) { 120f }
        val steady = series.steadyWindow()
        assertEquals(20, steady.size)
        assertTrue(steady.all { it == 120f })
    }

    @Test
    fun keepsEverythingWhenTheRateIsSteady() {
        val series = List(30) { 60f }
        assertEquals(30, series.steadyWindow().size)
    }

    @Test
    fun keepsEverythingWhenTheRateOnlyFalls() {
        val series = List(10) { 120f } + List(10) { 45f }
        assertEquals(20, series.steadyWindow().size)
    }

    @Test
    fun handlesAnEmptySeries() {
        assertTrue(emptyList<Float>().steadyWindow().isEmpty())
    }

    @Test
    fun theLobbyIsNotCountedAsADrop() {
        val series = List(10) { 90f } + List(20) { 120f }
        val threshold = 120f
        val drops = series.steadyWindow().count { it < threshold }
        assertEquals("the lobby must not count as dropped frames", 0, drops)
    }

    @Test
    fun aRealStallInsideTheActionStillCounts() {
        val series = List(10) { 90f } + List(18) { 120f } + listOf(72f, 119f)
        val drops = series.steadyWindow().count { it < 120f }
        assertEquals(2, drops)
    }
}
