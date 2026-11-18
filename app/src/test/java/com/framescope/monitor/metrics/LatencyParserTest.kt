package com.framescope.monitor.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shapes SurfaceFlinger prints, and the ones it prints when something is wrong.
 *
 * The dump format is not the same on every device or every Android version, so the
 * parser has to survive the variants rather than assume one of them.
 *
 * A frame series has to move forward at a steady pace. A dump that does not is not
 * a series of frames, and a frame rate read from it would belong to nothing.
 */
class LatencyParserTest {

    /** The three column layout: desired vsync, actual present, buffer ready. */
    private fun threeColumn(vararg rows: LongArray): String = buildString {
        appendLine("16666666")
        for (row in rows) {
            appendLine(row.joinToString(" "))
        }
    }

    /** A steady series of frames, one every 16.6 ms. */
    private fun steady(count: Int, start: Long = 1_000_000_000L, step: Long = 16_666_666L) =
        Array(count) { i ->
            longArrayOf(start + i * step, start + i * step + 1L, start + i * step + 2L)
        }

    @Test
    fun readsThePresentTimeOutOfTheSecondColumn() {
        val dump = threeColumn(*steady(4))
        val samples = parseLayerLatencies(dump)

        assertEquals(1, samples.size)
        assertEquals(
            listOf(1_000_000_001L, 1_016_666_667L, 1_033_333_333L, 1_049_999_999L),
            samples[0].presents
        )
        assertEquals(16_666_666L, samples[0].refreshPeriodNs)
    }

    @Test
    fun readsASingleColumnLayout() {
        val dump = buildString {
            appendLine("16666666")
            for (i in 0 until 4) {
                appendLine("${1_000_000_001L + i * 16_666_666L}")
            }
        }
        val samples = parseLayerLatencies(dump)

        assertEquals(1, samples.size)
        assertEquals(4, samples[0].presents.size)
        assertEquals(1_000_000_001L, samples[0].presents.first())
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
            *steady(4)
        )
        val samples = parseLayerLatencies(dump)

        assertEquals(1, samples.size)
        assertEquals(4, samples[0].presents.size)
        assertEquals(1_000_000_001L, samples[0].presents.first())
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
            appendLine("1050000000 1050000001 1050000002")
            appendLine("1066666666 1066666667 1066666668")
        }
        val samples = parseLayerLatencies(dump)

        assertEquals(1, samples.size)
        assertEquals(
            listOf(1_000_000_001L, 1_033_333_333L, 1_050_000_001L, 1_066_666_667L),
            samples[0].presents
        )
    }

    @Test
    fun theSameTimestampTwiceIsOneFrame() {
        val rows = steady(4)
        val dump = threeColumn(*rows, rows[3])
        val samples = parseLayerLatencies(dump)

        assertEquals(1, samples.size)
        assertEquals(4, samples[0].presents.size)
    }

    @Test
    fun timestampsThatArriveOutOfOrderArePutInOrder() {
        val rows = steady(6)
        val dump = threeColumn(*arrayOf(rows[4], rows[5], rows[0], rows[1], rows[2], rows[3]))
        val samples = parseLayerLatencies(dump)

        assertEquals(1, samples.size)
        assertEquals(
            listOf(
                1_000_000_001L, 1_016_666_667L, 1_033_333_333L,
                1_049_999_999L, 1_066_666_665L, 1_083_333_331L
            ),
            samples[0].presents
        )
    }

    @Test
    fun severalLayersKeepTheirOwnNames() {
        val dump = buildString {
            appendLine("@@@SurfaceView[com.example.game/Main](BLAST)#0")
            append(threeColumn(*steady(4)))
            appendLine("@@@SurfaceView[com.example.game/Main](BLAST)#1")
            append(threeColumn(*steady(4, start = 2_000_000_000L)))
        }
        val samples = parseLayerLatencies(dump)

        assertEquals(2, samples.size)
        assertEquals("SurfaceView[com.example.game/Main](BLAST)#0", samples[0].name)
        assertEquals("SurfaceView[com.example.game/Main](BLAST)#1", samples[1].name)
        assertEquals(1_000_000_001L, samples[0].presents.first())
        assertEquals(2_000_000_001L, samples[1].presents.first())
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
            append(threeColumn(*steady(4)).removePrefix("16666666\n"))
        }
        val samples = parseLayerLatencies(dump)

        assertEquals(1, samples.size)
        assertEquals(8_333_333L, samples[0].refreshPeriodNs)
        assertEquals("layer", samples[0].name)
    }

    @Test
    fun aSeriesThatDoesNotMoveSteadilyIsRefused() {
        // Frames that arrive in bursts are not a frame series, and a frame rate
        // read from them would be a number that belongs to nothing.
        val dump = threeColumn(
            longArrayOf(1_000_000_000L, 1_000_000_001L, 1_000_000_002L),
            longArrayOf(1_000_000_003L, 1_000_000_004L, 1_000_000_005L),
            longArrayOf(2_000_000_000L, 2_000_000_001L, 2_000_000_002L),
            longArrayOf(2_000_000_003L, 2_000_000_004L, 2_000_000_005L)
        )

        assertTrue(parseLayerLatencies(dump).isEmpty())
    }

    @Test
    fun theSeriesIsFoundInAnotherColumnWhenTheSecondOneIsRubbish() {
        // A build can order the columns differently. The column that moves
        // forward at a steady pace is the one with the frames in it.
        val dump = threeColumn(
            longArrayOf(1_000_000_000L, 7L, 9L),
            longArrayOf(1_016_666_666L, 7L, 9L),
            longArrayOf(1_033_333_332L, 7L, 9L),
            longArrayOf(1_050_000_000L, 7L, 9L)
        )
        val samples = parseLayerLatencies(dump)

        assertEquals(1, samples.size)
        assertEquals(
            listOf(1_000_000_000L, 1_016_666_666L, 1_033_333_332L, 1_050_000_000L),
            samples[0].presents
        )
    }
}
