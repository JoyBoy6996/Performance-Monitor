package com.framescope.monitor.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shapes SurfaceFlinger prints, and the ones it prints when something is wrong.
 *
 * The dump format is not the same on every device or every Android version, so the
 * parser has to survive the variants rather than assume one of them.
 */
class LatencyParserTest {

    /** The three column layout: desired vsync, actual present, buffer ready. */
    private fun threeColumn(vararg rows: LongArray): String = buildString {
        appendLine("16666666")
        for (row in rows) {
            appendLine(row.joinToString(" "))
        }
    }

    @Test
    fun readsThePresentTimeOutOfTheSecondColumn() {
        val dump = threeColumn(
            longArrayOf(1_000_000_000L, 1_000_000_001L, 1_000_000_002L),
            longArrayOf(1_016_666_666L, 1_016_666_667L, 1_016_666_668L)
        )
        val samples = parseLayerLatencies(dump)

        assertEquals(1, samples.size)
        assertEquals(listOf(1_000_000_001L, 1_016_666_667L), samples[0].presents)
        assertEquals(16_666_666L, samples[0].refreshPeriodNs)
    }

    @Test
    fun readsASingleColumnLayout() {
        val dump = buildString {
            appendLine("16666666")
            appendLine("1000000001")
            appendLine("1016666667")
        }
        val samples = parseLayerLatencies(dump)

        assertEquals(1, samples.size)
        assertEquals(listOf(1_000_000_001L, 1_016_666_667L), samples[0].presents)
    }

    @Test
    fun anEmptyDumpGivesNothing() {
        assertTrue(parseLayerLatencies("").isEmpty())
        assertTrue(parseLayerLatencies("   \n  ").isEmpty())
    }

    @Test
    fun aHeaderOnItsOwnGivesNothing() {
        assertTrue(parseLayerLatencies("16666666").isEmpty())
    }

    @Test
    fun theEmptyRowsAtTheFrontAreNotFrames() {
        // SurfaceFlinger marks the frames that were never drawn with a row of zeros.
        val dump = threeColumn(
            longArrayOf(0L, 0L, 0L),
            longArrayOf(1_000_000_000L, 1_000_000_001L, 1_000_000_002L)
        )
        val samples = parseLayerLatencies(dump)

        assertEquals(1, samples.size)
        assertEquals(listOf(1_000_000_001L), samples[0].presents)
    }

    @Test
    fun aRowWithSomethingThatIsNotANumberIsDropped() {
        // A stray token would shift every column after it, so the row is refused
        // instead of being read from the wrong column.
        val dump = buildString {
            appendLine("16666666")
            appendLine("1000000000 1000000001 1000000002")
            appendLine("1016666666 oops 1016666668")
            appendLine("1033333332 1033333333 1033333334")
        }
        val samples = parseLayerLatencies(dump)

        assertEquals(1, samples.size)
        assertEquals(
            listOf(1_000_000_001L, 1_033_333_333L),
            samples[0].presents
        )
    }

    @Test
    fun theSameTimestampTwiceIsOneFrame() {
        val dump = threeColumn(
            longArrayOf(1_000_000_000L, 1_000_000_001L, 1_000_000_002L),
            longArrayOf(1_000_000_000L, 1_000_000_001L, 1_000_000_002L)
        )
        val samples = parseLayerLatencies(dump)

        assertEquals(1, samples.size)
        assertEquals(listOf(1_000_000_001L), samples[0].presents)
    }

    @Test
    fun timestampsThatArriveOutOfOrderArePutInOrder() {
        val dump = threeColumn(
            longArrayOf(1_016_666_666L, 1_016_666_667L, 1_016_666_668L),
            longArrayOf(1_000_000_000L, 1_000_000_001L, 1_000_000_002L)
        )
        val samples = parseLayerLatencies(dump)

        assertEquals(
            listOf(1_000_000_001L, 1_016_666_667L),
            samples[0].presents
        )
    }

    @Test
    fun severalLayersKeepTheirOwnNames() {
        val dump = buildString {
            appendLine("@@@SurfaceView[com.example.game/Main](BLAST)#0")
            append(threeColumn(longArrayOf(1_000_000_000L, 1_000_000_001L, 1_000_000_002L)))
            appendLine("@@@SurfaceView[com.example.game/Main](BLAST)#1")
            append(threeColumn(longArrayOf(2_000_000_000L, 2_000_000_001L, 2_000_000_002L)))
        }
        val samples = parseLayerLatencies(dump)

        assertEquals(2, samples.size)
        assertEquals("SurfaceView[com.example.game/Main](BLAST)#0", samples[0].name)
        assertEquals("SurfaceView[com.example.game/Main](BLAST)#1", samples[1].name)
        assertEquals(listOf(1_000_000_001L), samples[0].presents)
        assertEquals(listOf(2_000_000_001L), samples[1].presents)
    }

    @Test
    fun aLargeRollingHistoryIsReadWhole() {
        // The dump is not truncated by the parser: the frames that are still in the
        // window are the frames that were presented.
        val rows = Array(128) { i ->
            longArrayOf(1_000_000_000L + i * 16_666_666L, 1_000_000_001L + i * 16_666_666L, 0L)
        }
        val samples = parseLayerLatencies(threeColumn(*rows))

        assertEquals(1, samples.size)
        assertEquals(128, samples[0].presents.size)
        assertEquals(1_000_000_001L, samples[0].presents.first())
    }

    @Test
    fun aLayerWithoutFramesIsNotReturned() {
        val dump = buildString {
            appendLine("@@@Broken[com.example.game]")
            appendLine("16666666")
            appendLine("0 0 0")
        }
        assertTrue(parseLayerLatencies(dump).isEmpty())
    }

    @Test
    fun theRefreshPeriodIsReadOnItsOwn() {
        val dump = buildString {
            appendLine("@@@layer")
            appendLine("8333333")
            appendLine("1000000000 1000000001 1000000002")
        }
        val samples = parseLayerLatencies(dump)

        assertEquals(1, samples.size)
        assertEquals(8_333_333L, samples[0].refreshPeriodNs)
        assertEquals("layer", samples[0].name)
    }
}
