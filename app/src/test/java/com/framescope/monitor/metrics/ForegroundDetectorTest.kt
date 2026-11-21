package com.framescope.monitor.metrics

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which app the app in front is.
 *
 * The window manager is exact, but it can name a window that is on the screen
 * and not in front: an overlay of another app, or a launcher that keeps the
 * focus while a game is drawn over it. The usage access knows which app was in
 * front a moment ago, and when the two answers differ clearly, the more recent
 * one is the app the user is looking at.
 */
class ForegroundDetectorTest {

    private fun privileged(pkg: String) =
        ForegroundDetector.Detection(pkg, ForegroundDetector.Source.PRIVILEGED, true)

    private fun usage(pkg: String) =
        ForegroundDetector.Detection(pkg, ForegroundDetector.Source.USAGE_STATS, false)

    @Test
    fun `the app that was in front more recently wins`() {
        val now = 1_000_000L
        val chosen = chooseForeground(
            privileged = privileged("com.android.launcher3"),
            usage = usage("com.pubg.imobile"),
            lastUsedAt = mapOf(
                "com.android.launcher3" to now - 600_000L,
                "com.pubg.imobile" to now
            )
        )

        assertEquals("com.pubg.imobile", chosen.packageName)
    }

    @Test
    fun `the window manager wins when both answers are about the same time`() {
        val now = 1_000_000L
        val chosen = chooseForeground(
            privileged = privileged("com.android.launcher3"),
            usage = usage("com.pubg.imobile"),
            lastUsedAt = mapOf(
                "com.android.launcher3" to now,
                "com.pubg.imobile" to now - 500L
            )
        )

        assertEquals("com.android.launcher3", chosen.packageName)
    }

    @Test
    fun `without usage access the window manager answers`() {
        val chosen = chooseForeground(
            privileged = privileged("com.pubg.imobile"),
            usage = ForegroundDetector.Detection(
                null, ForegroundDetector.Source.NONE, false
            ),
            lastUsedAt = emptyMap()
        )

        assertEquals("com.pubg.imobile", chosen.packageName)
    }

    @Test
    fun `without the window manager the usage access answers`() {
        val chosen = chooseForeground(
            privileged = ForegroundDetector.Detection(
                null, ForegroundDetector.Source.NONE, false
            ),
            usage = usage("com.pubg.imobile"),
            lastUsedAt = mapOf("com.pubg.imobile" to 1_000_000L)
        )

        assertEquals("com.pubg.imobile", chosen.packageName)
    }

    @Test
    fun `both answers naming the same app are the same answer`() {
        val chosen = chooseForeground(
            privileged = privileged("com.pubg.imobile"),
            usage = usage("com.pubg.imobile"),
            lastUsedAt = emptyMap()
        )

        assertEquals("com.pubg.imobile", chosen.packageName)
    }
}
