package com.framescope.monitor.metrics

/**
 * The frame counters SurfaceFlinger keeps for every layer.
 *
 * `dumpsys SurfaceFlinger --timestats` reports how many frames each layer
 * rendered and how many of them were late. Unlike the latency dump it does
 * not need a frame timeline, so it is the only source that also works for a
 * game that renders straight into a buffer queue.
 *
 * The text this reads is the one SurfaceFlinger really prints
 * (`TimeStatsHelper::TimeStatsLayer::toString()`):
 *
 * ```
 * displayRefreshRate = 144 fps
 * renderRate = 120 fps
 * uid = 10234
 * layerName = SurfaceView[com.pubg.imobile/com.pubg.imobile.MainActivity](BLAST)#0
 * packageName = com.pubg.imobile
 * totalFrames = 118
 * droppedFrames = 0
 * Jank payload for this layer:
 * totalTimelineFrames = 118
 * jankyFrames = 4
 * averageFPS = 118.532
 * ```
 *
 * Every block is introduced by its `layerName = ` line, but the header of the
 * block (refresh rate, render rate, uid) is printed *before* that line, so the
 * header is kept while the block is being read. The global legacy section
 * prints a `totalFrames` of its own; it is never read because no block has
 * started at that point - a counter that belongs to the display instead of the
 * app would be the one number this app must never report.
 *
 * The counters are cumulative: `totalFrames` is everything the layer has
 * presented since SurfaceFlinger was asked to collect. The frame rate is
 * therefore the growth between two dumps divided by the time between them,
 * never the total itself - and the dump is not cleared, because a clear throws
 * away the counters of a layer that has stopped drawing, which is exactly the
 * layer the overlay is looking at.
 */
object TimeStats {

    /** A reading of one layer's counters. */
    data class Reading(
        val layer: String,
        val frames: Long,
        val jankyFrames: Long,
        /**
         * Frames that were dropped or whose vsync was missed.
         *
         * The text prints this as `droppedFrames`; the old format called it
         * `Number Missed Vsync`, and both mean the same thing to the interface.
         */
        val missedVsync: Long = 0L,
        /** The average frame rate SurfaceFlinger computed for the window. */
        val averageFps: Float? = null,
        /** The frame rate the app asked the display for, in fps. */
        val renderRate: Int? = null,
        /** The refresh rate the display presented this layer at, in Hz. */
        val displayRefreshRate: Int? = null,
        /** The package SurfaceFlinger names for this layer, when it prints one. */
        val packageName: String? = null
    )

    /** `key = value`, as every line of the text is written. */
    private val PAIR = Regex("^([A-Za-z][A-Za-z0-9_]*)\\s*=\\s*(.*)$")

    /**
     * Reads the counters of every layer that belongs to a package.
     *
     * The dump names the layer and, on the builds that print it, its package.
     * A reading that names neither the package nor the layer is refused: a
     * global counter would be the display's frame rate and not the app's.
     */
    fun readAll(dump: String, packageName: String): List<Reading> {
        val all = readAllUnfiltered(dump)
        if (packageName.isBlank()) return all
        return all.filter { belongsTo(it, packageName) }
    }

    /**
     * Reads the counters of every layer in the dump, whatever it is called.
     *
     * Used when no layer carries the package name: the layer whose counters grew the
     * fastest is the app in front, because only the visible app draws that many
     * frames in a fraction of a second.
     */
    fun readAllUnfiltered(dump: String): List<Reading> {
        if (dump.isBlank()) return emptyList()
        val modern = readModern(dump)
        if (modern.isNotEmpty()) return modern
        return readLegacy(dump)
    }

    /** Reads the counters of the layer of a package that drew the most. */
    fun read(dump: String, packageName: String): Reading? =
        readAll(dump, packageName).maxByOrNull { it.frames }

    /** True when a reading can belong to the package. */
    fun belongsTo(reading: Reading, packageName: String): Boolean {
        if (packageName.isBlank()) return true
        if (reading.packageName?.equals(packageName, ignoreCase = true) == true) return true
        return reading.layer.contains(packageName, ignoreCase = true)
    }

    // --- the format every current build prints ---------------------------------

    private fun readModern(dump: String): List<Reading> {
        if (!dump.contains("layerName = ")) return emptyList()

        val out = ArrayList<Reading>()
        var current: MutableMap<String, String>? = null
        // The header of the next block is printed before its layer name, so it
        // is held here until the block that owns it starts.
        val pending = HashMap<String, String>()

        fun flush() {
            val fields = current ?: return
            val layer = fields["layername"].orEmpty()
            if (layer.isNotBlank()) {
                out += Reading(
                    layer = layer,
                    frames = fields["totalframes"]?.toLongOrNull() ?: 0L,
                    jankyFrames = fields["jankyframes"]?.toLongOrNull() ?: 0L,
                    missedVsync = fields["droppedframes"]?.toLongOrNull() ?: 0L,
                    averageFps = fields["averagefps"]?.toFloatOrNull(),
                    renderRate = fpsValue(fields["renderrate"]),
                    displayRefreshRate = fpsValue(fields["displayrefreshrate"]),
                    packageName = fields["packagename"]
                        ?.takeIf { it.isNotBlank() && it != "none" }
                )
            }
            current = null
        }

        for (raw in dump.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val match = PAIR.find(line) ?: continue
            val key = match.groupValues[1].lowercase()
            val value = match.groupValues[2].trim()

            if (key == "layername") {
                flush()
                val block = HashMap<String, String>()
                pending["displayrefreshrate"]?.let { block["displayrefreshrate"] = it }
                pending["renderrate"]?.let { block["renderrate"] = it }
                pending["uid"]?.let { block["uid"] = it }
                block["layername"] = value
                current = block
                pending.clear()
                continue
            }

            val fields = current
            if (fields == null) {
                pending[key] = value
            } else if (key == "displayrefreshrate" && fields.containsKey(key)) {
                // The next block has started: its header comes before its layer name.
                flush()
                pending[key] = value
            } else {
                fields[key] = value
            }
        }
        flush()
        return out
    }

    /** `120 fps` becomes 120; `0 fps` and an empty value become null. */
    private fun fpsValue(raw: String?): Int? =
        raw?.trim()?.takeWhile { it.isDigit() }?.toIntOrNull()?.takeIf { it > 0 }

    // --- the format older builds print -----------------------------------------

    private val FRAMES_RE = Regex("Total frames rendered:\\s*(\\d+)", RegexOption.IGNORE_CASE)
    private val JANKY_RE = Regex("Janky frames:\\s*(\\d+)", RegexOption.IGNORE_CASE)
    private val MISSED_RE = Regex("Missed Vsync:\\s*(\\d+)", RegexOption.IGNORE_CASE)

    /**
     * The old text, which named a layer on a line of its own and put its
     * counters under it. Kept so an older phone still gets a reading.
     */
    private fun readLegacy(dump: String): List<Reading> {
        val blocks = mutableListOf<Triple<String, Long, Pair<Long, Long>>>()
        var layer = ""
        var frames: Long? = null
        var janky = 0L
        var missed = 0L

        fun flush() {
            frames?.let { blocks += Triple(layer, it, janky to missed) }
            frames = null
            janky = 0L
            missed = 0L
        }

        for (raw in dump.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) {
                flush()
                continue
            }
            FRAMES_RE.find(line)?.let { frames = it.groupValues[1].toLongOrNull() }
            JANKY_RE.find(line)?.let { janky = it.groupValues[1].toLongOrNull() ?: janky }
            MISSED_RE.find(line)?.let { missed = it.groupValues[1].toLongOrNull() ?: missed }
            // A line that names a surface and carries no number starts a block.
            if (!line.contains(":") && line.length > 3 &&
                (line.contains("[") || line.contains("(") || line.contains("Surface"))
            ) {
                flush()
                layer = line
            }
        }
        flush()

        return blocks.map { Reading(it.first, it.second, it.third.first, it.third.second) }
    }
}
