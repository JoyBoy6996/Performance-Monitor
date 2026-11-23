package com.framescope.monitor.ui.components

import androidx.compose.ui.graphics.Color
import com.framescope.monitor.core.LivePoint
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * One reading on a chart: the value and the moment it belongs to.
 *
 * Every chart in this app is built from points that know their own time. A list of
 * values without a time cannot be drawn honestly: a run that lost its readings for
 * ten seconds would look the same as one that never missed a beat.
 */
data class ChartPoint(
    /** Milliseconds since the start of the recording. */
    val timeMs: Long,
    val value: Float
)

/**
 * Something that happened during the run, drawn on the time axis.
 *
 * The events come from the recording itself: a change of the frame rate cap, a
 * thermal level that went up, a second that was dropped.
 */
data class ChartEvent(
    val timeMs: Long,
    val label: String,
    val color: Color
)

/**
 * One line in a chart.
 *
 * All the lines of one chart share a unit and a scale, because a chart that mixes
 * two scales into one pair of axes says something that is not true. Two metrics
 * with different units belong in two charts that share a moment, not in one.
 */
data class ChartSeries(
    val label: String,
    val unit: String,
    val color: Color,
    val points: List<ChartPoint>,
    /** The bottom of the scale when it should not follow the data. */
    val min: Float? = null,
    /** The top of the scale when it should not follow the data. */
    val max: Float? = null,
    /** A line at this value, e.g. the frame rate the game is aiming for. */
    val target: Float? = null
)

/**
 * The scale of one chart.
 *
 * The scale is built from the data and never from a guess, and it always contains
 * the target line so a reading can be compared with it.
 */
data class ChartScale(val min: Float, val max: Float) {

    /** Where a value sits in the plot, from 0 at the bottom to 1 at the top. */
    fun fraction(value: Float): Float {
        val span = max - min
        if (span <= 0f) return 0.5f
        return ((value - min) / span).coerceIn(0f, 1f)
    }
}

/**
 * Builds the scale of a chart.
 *
 * The scale covers every value of the chart and the target, with a little room
 * above and below so the highest and the lowest reading are not drawn on the
 * frame of the chart.
 */
fun buildChartScale(
    values: List<Float>,
    pinnedMin: Float? = null,
    pinnedMax: Float? = null,
    target: Float? = null
): ChartScale {
    val clean = values.filter { it.isFinite() }
    if (clean.isEmpty()) return ChartScale(0f, 1f)

    var low = pinnedMin ?: clean.min()
    var high = pinnedMax ?: clean.max()

    if (target != null && target.isFinite()) {
        if (pinnedMin == null) low = minOf(low, target)
        if (pinnedMax == null) high = maxOf(high, target)
    }

    if (high - low <= 0f) {
        // One value, or every value the same: give it a scale of its own so the line
        // sits in the middle instead of on the frame.
        val centre = (high + low) / 2f
        val pad = if (abs(centre) > 0.001f) abs(centre) * 0.1f else 1f
        return ChartScale(centre - pad, centre + pad)
    }

    val pad = (high - low) * 0.08f
    return ChartScale(low - pad, high + pad)
}

/**
 * The nearest sample to a moment.
 *
 * This is the policy the whole app uses when two metrics have to be lined up: the
 * sample that is closest in time wins, and the chart says which sample it was.
 * Nothing is invented and nothing is interpolated.
 */
fun nearestPoint(points: List<ChartPoint>, timeMs: Long): ChartPoint? {
    if (points.isEmpty()) return null
    if (points.size == 1) return points.first()

    var low = 0
    var high = points.size - 1
    while (low < high) {
        val middle = (low + high) / 2
        if (points[middle].timeMs < timeMs) low = middle + 1 else high = middle
    }
    val after = points[low]
    if (low == 0) return after
    val before = points[low - 1]
    return if (abs(after.timeMs - timeMs) <= abs(timeMs - before.timeMs)) after else before
}

/**
 * The ticks of the time axis.
 *
 * The ticks are spread over the real length of the run, so a run with a gap shows
 * that gap instead of hiding it.
 */
fun buildTimeTicks(startMs: Long, endMs: Long, tickCount: Int = 5): List<Long> {
    if (tickCount < 2) return listOf(startMs)
    if (endMs <= startMs) return listOf(startMs)
    val step = (endMs - startMs).toDouble() / (tickCount - 1)
    return (0 until tickCount).map { index -> startMs + (step * index).roundToLong() }
}

/**
 * A moment of a run as it is printed.
 *
 * A run that is longer than an hour prints "1:02:03", otherwise "2:03".
 */
fun formatElapsed(ms: Long): String {
    val safe = ms.coerceAtLeast(0L)
    val totalSeconds = safe / 1000L
    val seconds = totalSeconds % 60L
    val minutes = (totalSeconds / 60L) % 60L
    val hours = totalSeconds / 3600L
    return if (hours > 0L) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}

/**
 * The ranges a chart of a recording can show.
 *
 * A range is a window of the run, not a filter that makes up data: what is inside
 * the window is what was recorded, and what is not there was never recorded.
 */
enum class ChartRange(val label: String, val windowMs: Long?) {
    ONE_MINUTE("1m", 60_000L),
    FIVE_MINUTES("5m", 300_000L),
    FIFTEEN_MINUTES("15m", 900_000L),
    THIRTY_MINUTES("30m", 1_800_000L),
    FULL("Full", null);

    companion object {
        /**
         * The ranges that make sense for a run of [durationMs].
         *
         * A range longer than the run would show the same thing as the full run, so
         * it is not offered.
         */
        fun availableFor(durationMs: Long): List<ChartRange> =
            entries.filter { range ->
                range.windowMs == null || range.windowMs < durationMs
            }.ifEmpty { listOf(FULL) }
    }
}

/**
 * The part of a series that falls inside a window of the run.
 *
 * The window ends at the last recorded moment, so a range of "the last minute"
 * shows the last minute of the recording and not the last minute of the clock.
 */
fun List<ChartPoint>.withinWindow(
    startMs: Long,
    endMs: Long
): List<ChartPoint> = filter { it.timeMs in startMs..endMs }

/**
 * Draws a long series with fewer points without hiding what happened.
 *
 * A run of an hour has thousands of samples and a phone cannot draw all of them
 * while a finger is on the screen. Every bucket keeps its lowest and its highest
 * reading, so a drop and a spike survive the drawing. The data itself is not
 * changed: the tooltip still reads the original samples.
 */
fun downsamplePreservingSpikes(points: List<ChartPoint>, maxPoints: Int): List<ChartPoint> {
    if (maxPoints < 4 || points.size <= maxPoints) return points

    val bucketSize = ((points.size + (maxPoints - 2) - 1) / (maxPoints - 2)).coerceAtLeast(1)
    val out = ArrayList<ChartPoint>(maxPoints + 2)
    out.add(points.first())

    var index = 0
    while (index < points.size) {
        val end = minOf(index + bucketSize, points.size)
        var low = index
        var high = index
        for (k in index until end) {
            if (points[k].value < points[low].value) low = k
            if (points[k].value > points[high].value) high = k
        }
        if (low == high) {
            out.add(points[low])
        } else {
            val first = minOf(low, high)
            val second = maxOf(low, high)
            out.add(points[first])
            out.add(points[second])
        }
        index = end
    }

    if (out.last().timeMs != points.last().timeMs) out.add(points.last())
    return out
}

/**
 * The samples of a recorded series as chart points.
 *
 * A run that kept the moment of every second is drawn on its own clock. A run
 * that was recorded before the app kept those moments falls back to one second
 * per sample, which is what the recorder did back then.
 */
fun chartPoints(values: List<Float>, timesMs: List<Long>): List<ChartPoint> {
    if (values.isEmpty()) return emptyList()
    val timed = timesMs.size == values.size && timesMs.isNotEmpty()
    return values.mapIndexedNotNull { index, value ->
        if (!value.isFinite()) return@mapIndexedNotNull null
        val time = if (timed) timesMs[index] else index * 1000L
        ChartPoint(time, value)
    }.sortedBy { it.timeMs }
}

/** True when a run knows the moment of every one of its samples. */
fun hasRealTimes(timesMs: List<Long>, values: List<Float>): Boolean =
    timesMs.isNotEmpty() && timesMs.size == values.size

/**
 * The live readings as chart points.
 *
 * The moments are made relative to the first one, so the time axis of a live
 * graph starts at zero instead of at the uptime of the phone.
 */
fun List<LivePoint>.asChartPoints(): List<ChartPoint> {
    if (isEmpty()) return emptyList()
    val first = first().timeMs
    return mapNotNull { point ->
        if (!point.value.isFinite()) return@mapNotNull null
        ChartPoint(point.timeMs - first, point.value)
    }
}
