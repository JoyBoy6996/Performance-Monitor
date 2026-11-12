package com.framescope.monitor.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The probe report is the evidence behind a reading.
 *
 * When a game shows no frames, the report has to say which layer was tried and
 * why it was dropped, otherwise every fix is a guess.
 */
class ProbeReportTest {

    private fun layer(name: String, body: String): String = "@@@$name\n$body"

    @Test
    fun `names the app and every layer it tried`() {
        val dump = layer(
            "SurfaceView[com.pubg.krmobile/com.pubg.krmobile.MainActivity](BLAST)#0",
            "16666666\n1000000 1000000 1000000 1000000\n"
        )
        val report = buildProbeReport("com.pubg.krmobile", dump)

        assertTrue(report.contains("app: com.pubg.krmobile"))
        assertTrue(report.contains("SurfaceView[com.pubg.krmobile"))
    }

    @Test
    fun `a layer without frames is reported as such`() {
        val dump = layer("SurfaceView[com.pubg.krmobile/x](BLAST)#0", "16666666\n")
        val report = buildProbeReport("com.pubg.krmobile", dump)

        assertTrue(report.contains("no frames"))
    }

    @Test
    fun `a layer with frames is reported with the median interval`() {
        val rows = (0..20).joinToString("\n") { (1_000_000L * it * 10).toString() }
        val dump = layer("SurfaceView[com.pubg.krmobile/x](BLAST)#0", "10000000\n$rows\n")
        val report = buildProbeReport("com.pubg.krmobile", dump)

        assertTrue(report.contains("ok"))
        assertTrue(report.contains("10.0 ms"))
    }

    @Test
    fun `an empty dump says that no layer answered`() {
        val report = buildProbeReport("com.pubg.krmobile", "")

        assertTrue(report.contains("no layer answered"))
    }

    @Test
    fun `the report of an app survives a switch to another app`() {
        val first = retainReport(emptyMap(), "com.pubg.krmobile", "app: com.pubg.krmobile")
        val second = retainReport(first, "com.framescope.live", "app: com.framescope.live")

        assertTrue(second.containsKey("com.pubg.krmobile"))
        assertEquals(listOf("com.pubg.krmobile", "com.framescope.live"), second.keys.toList())
    }

    @Test
    fun `an app that is sampled again moves to the end`() {
        val first = retainReport(emptyMap(), "a", "1")
        val second = retainReport(first, "b", "2")
        val third = retainReport(second, "a", "3")

        assertEquals(listOf("b", "a"), third.keys.toList())
    }

    @Test
    fun `the oldest report is dropped when there are too many`() {
        var map = emptyMap<String, String>()
        listOf("a", "b", "c", "d", "e").forEach { map = retainReport(map, it, it) }

        assertEquals(4, map.size)
        assertTrue(!map.containsKey("a"))
    }

    @Test
    fun `the raw rows of the dump are included`() {
        val dump = layer("L1", "16666666\n0 0 0\n100 200 300 400\n")
        val report = buildProbeReport("pkg", dump)

        assertTrue(report.contains("| "))
        assertEquals(2, report.lines().count { it.trim().startsWith("|") })
        assertTrue(report.contains("0 0 0"))
    }
}
