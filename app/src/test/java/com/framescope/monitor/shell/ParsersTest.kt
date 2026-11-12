package com.framescope.monitor.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the parsers against output that real Android builds produce.
 */
class ParsersTest {

    @Test
    fun readsTheForegroundPackageFromActivityDumps() {
        val dump = """
            ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities):
                mResumedActivity: ActivityRecord{9a3f21 u0 com.example.game/.MainActivity t42}
                topResumedActivity=ActivityRecord{9a3f21 u0 com.example.game/.MainActivity t42}
        """.trimIndent()
        assertEquals("com.example.game", Parsers.foregroundPackage(dump))
    }

    @Test
    fun readsTheForegroundPackageFromTheWindowManager() {
        val dump = """
            mCurrentFocus=Window{4c1e0 u0 com.android.settings/com.android.settings.Settings}
            mFocusedApp=ActivityRecord{0e12 u0 com.android.settings/.Settings t7}
        """.trimIndent()
        assertEquals("com.android.settings", Parsers.foregroundPackage(dump))
    }

    @Test
    fun returnsNullWhenThereIsNoForegroundApp() {
        assertNull(Parsers.foregroundPackage(""))
        assertNull(Parsers.foregroundPackage("WindowManager Global State:\n  mDisplayFrozen=false"))
    }

    @Test
    fun parsesTheThreeColumnLatencyLayout() {
        // Modern SurfaceFlinger writes desired / actual / ready per frame.
        val builder = StringBuilder("16666666\n")
        var desired = 1_000_000_000L
        var actual = 1_000_000_000L
        repeat(20) {
            desired += 16_666_666L
            actual += 16_666_700L
            builder.append("$desired $actual ${actual - 1_000_000L}\n")
        }
        val (refresh, presents) = Parsers.parseLatency(builder.toString())
        assertEquals(16_666_666L, refresh)
        assertEquals(20, presents.size)
        // The actual present times are the second column.
        assertEquals(1_016_666_700L, presents.first())
        assertTrue(presents.zipWithNext { a, b -> b - a }.all { it in 16_000_000L..17_500_000L })
    }

    @Test
    fun parsesTheTwoColumnLatencyLayout() {
        val builder = StringBuilder("16666666\n")
        var desired = 5_000_000_000L
        var actual = 5_000_000_000L
        repeat(20) {
            desired += 16_666_666L
            actual += 16_666_640L
            builder.append("$desired $actual\n")
        }
        val (_, presents) = Parsers.parseLatency(builder.toString())
        assertEquals(20, presents.size)
        assertEquals(5_016_666_640L, presents.first())
    }

    @Test
    fun parsesTheLayoutWithALeadingZeroRow() {
        // SurfaceFlinger starts with a "0 0 0" placeholder row for a frame that
        // was never drawn. A parser that does not drop it reads the wrong column.
        val builder = StringBuilder("16666666\n0 0 0\n")
        var desired = 2_000_000_000L
        var actual = 2_000_000_000L
        repeat(20) {
            desired += 16_666_666L
            actual += 16_666_700L
            builder.append("$desired $actual ${actual - 500_000L}\n")
        }
        val (_, presents) = Parsers.parseLatency(builder.toString())
        assertEquals(20, presents.size)
        assertEquals(2_016_666_700L, presents.first())
    }

    @Test
    fun rejectsLatencyOutputThatIsOnlyIdleGaps() {
        // One frame every two seconds is not a frame rate.
        val builder = StringBuilder("16666666\n")
        var time = 1_000_000_000L
        repeat(8) {
            builder.append("$time $time $time\n")
            time += 2_000_000_000L
        }
        val (_, presents) = Parsers.parseLatency(builder.toString())
        assertTrue(presents.isEmpty())
    }

    @Test
    fun ignoresLatencyOutputWithoutFrames() {
        val (_, presents) = Parsers.parseLatency("16666666\n")
        assertTrue(presents.isEmpty())
    }

    @Test
    fun readsThermalTemperaturesWhateverTheFieldOrder() {
        val dump = """
            Temperature{mValue=41.5, mType=2, mName=CPU, mStatus=0, mThermalStatus=0, mCanThrottle=true}
            Temperature{mValue=38.0, mType=1, mName=SKIN, mStatus=0, mThermalStatus=0, mCanThrottle=true}
        """.trimIndent()
        val thermals = Parsers.parseThermals(dump)
        assertEquals(41.5f, thermals["CPU"]!!, 0.01f)
        assertEquals(38.0f, thermals["SKIN"]!!, 0.01f)
    }

    @Test
    fun readsThermalTemperaturesWhenTheNameComesFirst() {
        val dump = "Temperature{mName=CPU, mValue=42.25, mType=2}"
        assertEquals(42.25f, Parsers.parseThermals(dump)["CPU"]!!, 0.01f)
    }

    @Test
    fun readsBatteryState() {
        val dump = """
            Current Battery Service state:
              AC powered: false
              USB powered: true
              status: 2
              health: 2
              present: true
              level: 78
              scale: 100
              voltage: 4321
              temperature: 305
              current now: 850000
              technology: Li-ion
        """.trimIndent()
        val battery = Parsers.parseBattery(dump)
        assertEquals("78", battery["level"])
        assertEquals("305", battery["temperature"])
        assertEquals("850000", battery["current now"])
        assertEquals("4321", battery["voltage"])
    }

    @Test
    fun readsMemoryTotals() {
        val dump = """
            MemTotal:        7851496 kB
            MemFree:          312044 kB
            MemAvailable:    5033212 kB
        """.trimIndent()
        val (total, available) = Parsers.parseMemInfo(dump)
        assertEquals(7851496L * 1024, total)
        assertEquals(5033212L * 1024, available)
    }

    @Test
    fun readsCpuTicks() {
        val dump = """
            cpu  1000 200 300 4000 500 600 700 800 0 0
            cpu0 500 100 150 2000 250 300 350 400 0 0
        """.trimIndent()
        val stats = Parsers.parseCpuStat(dump)
        assertNotNull(stats)
        assertEquals(1000L + 200 + 300 + 4000 + 500 + 600 + 700 + 800, stats!![0])
        assertEquals(4000L + 500, stats[1])
    }

    @Test
    fun splitsBatchedOutputBackIntoParts() {
        val output = "__FS_SEP__\nfirst\n__FS_SEP__\nsecond\n"
        val parts = ShellCommands.split(output)
        assertEquals(listOf("first", "second"), parts)
    }

    @Test
    fun keepsEmptyPartsSoTheIndicesStillMatch() {
        val output = "__FS_SEP__\n__FS_SEP__\nsecond\n"
        val parts = ShellCommands.split(output)
        assertEquals(2, parts.size)
        assertEquals("", parts[0])
        assertEquals("second", parts[1])
    }

    @Test
    fun findsTheRefreshRate() {
        assertEquals(120f, Parsers.parseRefreshRate("  refreshRate=120.0 (explicit)")!!, 0.01f)
    }
}
