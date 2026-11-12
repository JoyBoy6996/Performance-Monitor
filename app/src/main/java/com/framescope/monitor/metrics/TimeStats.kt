package com.framescope.monitor.metrics

/**
 * The frame counters SurfaceFlinger keeps for every layer.
 *
 * `dumpsys SurfaceFlinger --timestats` reports how many frames each layer
 * rendered and how many of them were late. Unlike the latency dump it does
 * not need a frame timeline, so it is the only source that also works for a
 * game that renders straight into a buffer queue.
 *
 * The counters are cumulative, so the frame rate is the growth of the counter
 * over the time between two readings.
 */
object TimeStats {

    /** A reading of one layer's counters. */
    data class Reading(
        val layer: String,
        val frames: Long,
        val jankyFrames: Long,
        val missedVsync: Long
    )

    private val FRAMES_RE = Regex("Total frames rendered:\\s*(\\d+)", RegexOption.IGNORE_CASE)
    private val JANKY_RE = Regex("Janky frames:\\s*(\\d+)", RegexOption.IGNORE_CASE)
    private val MISSED_RE = Regex("Missed Vsync:\\s*(\\d+)", RegexOption.IGNORE_CASE)

    /**
     * Reads the counters of every layer that belongs to a package.
     *
     * The dump names the layer it belongs to. If it does not, the reading is
     * refused: a global counter would be the display's frame rate and not the
     * app's.
     */
    fun readAll(dump: String, packageName: String): List<Reading> {
        val blocks = mutableListOf<Triple<String, Long, Pair<Long, Long>>>()
        var layer = ""
        var frames: Long? = null
        var janky = 0L
        var missed = 0L

        for (raw in dump.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) {
                frames?.let { blocks += Triple(layer, it, janky to missed) }
                frames = null
                janky = 0L
                missed = 0L
                continue
            }
            FRAMES_RE.find(line)?.let { frames = it.groupValues[1].toLongOrNull() }
            JANKY_RE.find(line)?.let { janky = it.groupValues[1].toLongOrNull() ?: janky }
            MISSED_RE.find(line)?.let { missed = it.groupValues[1].toLongOrNull() ?: missed }
            // A line that names a surface and carries no number starts a block.
            if (!line.contains(":") && line.length > 3 &&
                (line.contains("[") || line.contains("(") || line.contains("Surface"))
            ) {
                frames?.let { blocks += Triple(layer, it, janky to missed) }
                frames = null
                janky = 0L
                missed = 0L
                layer = line
            }
        }
        frames?.let { blocks += Triple(layer, it, janky to missed) }

        return blocks.filter { it.first.contains(packageName) }
            .map { Reading(it.first, it.second, it.third.first, it.third.second) }
    }

    /** Reads the counters of the first layer that belongs to a package. */
    fun read(dump: String, packageName: String): Reading? = readAll(dump, packageName).firstOrNull()
}
