package com.framescope.monitor.metrics

/** One SurfaceFlinger layer and the frame timestamps it produced. */
data class LayerSample(
    val name: String,
    val refreshPeriodNs: Long,
    val presents: List<Long>
)

/**
 * The panel's real refresh rate, from the first line of `dumpsys SurfaceFlinger --latency`.
 *
 * DisplayManager reports the rate of the calling app's window, which an app can be
 * held to even while the panel runs faster. SurfaceFlinger prints the period of the
 * display itself: 6944444 ns is exactly 144 Hz. The period is the only number read
 * here - no frame timestamp is used, so an idle screen answers just as well.
 */
fun refreshHzFromLatencyDump(dump: String): Float? {
    val periodNs = dump.lineSequence()
        .mapNotNull { line -> line.trim().split(WHITESPACE).firstOrNull()?.toLongOrNull() }
        .firstOrNull() ?: return null
    // Sixteen milliseconds is 60 Hz; fifty is 20 Hz. Anything outside this
    // range is a different unit or a broken dump, not a display.
    if (periodNs <= 0L || periodNs > 50_000_000L) return null
    val hz = 1_000_000_000f / periodNs
    return hz.takeIf { it in 30f..360f }
}

/** What kind of surface a layer is. */
enum class LayerKind {
    /** A buffer queue a renderer draws into - where a game draws. */
    SURFACE_VIEW,
    /** The window of an activity. */
    WINDOW,
    /** A system or overlay surface. */
    SYSTEM,
    /** Anything that could not be classified. */
    UNKNOWN
}

/** How sure we are that a layer belongs to the foreground app. */
enum class PackageEvidence {
    /** The layer names the foreground package outright. */
    EXACT,
    /** The layer mentions the package but not in the usual place. */
    PARTIAL,
    /** The layer says nothing about the foreground package. */
    NONE
}

/** One layer with everything known about it. */
data class LayerCandidate(
    val sample: LayerSample,
    val kind: LayerKind,
    val evidence: PackageEvidence,
    /** The newest presentation timestamp of this layer. */
    val lastPresentNs: Long
)

/** The result of looking for the layer that belongs to the foreground app. */
data class TargetSelection(
    /** The layer that was verified, or null when nothing could be verified. */
    val target: LayerSample?,
    /** Why that layer was chosen, or why no layer could be chosen. */
    val reason: String,
    /** Every layer that was considered. */
    val candidates: List<LayerCandidate> = emptyList(),
    /** Every layer that was dropped, and why it was dropped. */
    val rejected: List<Pair<LayerCandidate, String>> = emptyList()
) {
    val verified: Boolean get() = target != null
}

private val WHITESPACE = Regex("\\s+")
private val BRACKET = Regex("\\[([^\\]]+)\\]")

/**
 * Splits the multi-layer dump into one sample per layer.
 *
 * The dump is written by a shell loop that prefixes every block with `@@@<layer name>`.
 * A block is then the refresh period on its own line followed by one row per frame.
 *
 * Rows that are not made of numbers only are dropped rather than partially read: a row
 * with a stray token would shift every column after it, and a frame that was parsed from
 * the wrong column is worse than a frame that was not parsed at all.
 */
fun parseLayerLatencies(dump: String): List<LayerSample> {
    if (dump.isBlank()) return emptyList()

    val samples = mutableListOf<LayerSample>()
    for (block in splitBlocks(dump)) {
        val body = block.body
        if (body.isEmpty()) continue

        val refreshPeriodNs = body.first()
            .split(WHITESPACE)
            .firstOrNull()
            ?.toLongOrNull()
            ?: 0L

        // A row that holds a value that is not a number is refused. The columns
        // of a row have to line up with the columns of the other rows, and one
        // stray token would shift every column after it.
        val rows = body.drop(1)
            .map { row -> readRow(row) }
            .filter { it.isNotEmpty() }

        // The first row is the placeholder for "this frame was never drawn".
        val real = if (rows.firstOrNull()?.all { it == 0L } == true) rows.drop(1) else rows
        if (real.isEmpty()) continue

        val series = readFrameSeries(real, refreshPeriodNs) ?: continue

        // The same timestamp can appear twice; a frame is one timestamp.
        // Presentation timestamps arrive in order, but a dump taken while the
        // pipeline was catching up can be out of order.
        val presents = series.distinct().sorted()

        if (presents.isNotEmpty()) {
            samples.add(LayerSample(block.name, refreshPeriodNs, presents))
        }
    }

    return samples
}

/** The numbers of one row, or nothing when the row holds something else. */
private fun readRow(row: String): List<Long> {
    val columns = row.trim().split(WHITESPACE).filter { it.isNotEmpty() }
    if (columns.isEmpty()) return emptyList()
    val numbers = columns.map { it.toLongOrNull() ?: return emptyList() }
    return numbers
}

/**
 * The frame series of a block.
 *
 * The present time is normally the second column, but a build can order the
 * columns differently, so every column is tried and the first one that moves
 * forward at a steady pace wins.
 *
 * A series that does not move steadily is refused. A wrong series is worse
 * than no series: it would report a frame rate that belongs to nothing.
 */
private fun readFrameSeries(rows: List<List<Long>>, refreshNs: Long): List<Long>? {
    val columns = rows.first().size
    for (index in preferredColumns(columns)) {
        val series = rows.mapNotNull { it.getOrNull(index) }
            // A record that was never filled keeps FrameTracker's sentinel,
            // INT64_MAX, and a zero means "no frame". Both are the absence of
            // a frame, not a frame that was presented 292 years after boot.
            .filter { it > 0L && it < MAX_PLAUSIBLE_PRESENT_NS }
        if (looksLikeFrameSeries(series, refreshNs)) return series
    }
    return null
}

/** A monotonic timestamp larger than this is a sentinel, not a time. */
private const val MAX_PLAUSIBLE_PRESENT_NS = 1_000_000_000_000_000_000L

/** The columns that can hold the present time, in the order they are tried. */
private fun preferredColumns(columns: Int): List<Int> = when {
    columns >= 3 -> listOf(1, 2, 0)
    columns == 2 -> listOf(1, 0)
    else -> listOf(0)
}

/** A real frame series moves forward at a steady pace. */
private fun looksLikeFrameSeries(series: List<Long>, refreshNs: Long = 0L): Boolean {
    if (series.size < 4) return false
    val deltas = series.zipWithNext { a, b -> b - a }.filter { it > 0L }
    if (deltas.size < 3) return false
    val median = deltas.sorted()[deltas.size / 2]
    // A frame is never presented in under a millisecond and never takes a
    // second; anything else is not a frame time.
    if (median < 1_000_000L || median > 1_000_000_000L) return false
    // A screen cannot present more frames than it refreshes. A column that
    // advances twice per refresh period is not the frame time - it is the
    // other column of the same row, and reading it gives a frame rate that no
    // screen can reach.
    if (refreshNs > 0L && median * 10 < refreshNs * 8) return false
    val clustered = deltas.count { it >= median / 2 && it <= median * 2 }
    return clustered * 10 >= deltas.size * 6
}

private data class RawBlock(val name: String, val body: List<String>)

/** Splits a dump into its blocks, keeping the layer name each block was tagged with. */
private fun splitBlocks(dump: String): List<RawBlock> {
    if (dump.contains("@@@")) {
        return dump.split("@@@")
            .filter { it.isNotBlank() }
            .map { block ->
                val lines = block.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
                RawBlock(name = lines.firstOrNull().orEmpty(), body = lines.drop(1))
            }
    }
    val lines = dump.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
    return listOf(RawBlock(name = "", body = lines))
}

/**
 * The layer name SurfaceFlinger itself answers to.
 *
 * `dumpsys SurfaceFlinger --list` does not print layer names. It prints
 * `RequestedLayerState{<name> parentId=52 !handle z=6}`:
 *
 * ```cpp
 * // RequestedLayerState::getDebugString()
 * debug << "RequestedLayerState{" << name;
 * if (parentId != UNASSIGNED_LAYER_ID) debug << " parentId=" << parentId;
 * ...
 * ```
 *
 * `--latency <layer>` compares the argument with `layer->getName()` *exactly*:
 *
 * ```cpp
 * // SurfaceFlinger::dumpStats()
 * StringAppendF(&result, "%" PRId64 "\n", mScheduler->getPacesetterVsyncPeriod().ns());
 * traverseLegacyLayers([&](Layer* layer) {
 *     if (layer->getName() == name.c_str()) layer->dumpFrameStats(result);
 * });
 * ```
 *
 * A name that does not match prints nothing but that first line - the refresh
 * period - which looks like a layer with no frames. Every probe of every layer
 * answered with a single `6944444` (1/144 s) for exactly this reason: the
 * wrapper and the attributes were handed over as part of the name.
 *
 * The creation stamp inside the name (`... 11-19 19:36:02.082#750494`) is part
 * of it and is kept. Only what SurfaceFlinger appends *after* the name is
 * removed, because that is all `getDebugString()` adds.
 */
fun surfaceLayerName(listing: String): String {
    var name = listing.trim()
    if (name.startsWith(REQUESTED_LAYER_STATE)) {
        name = name.removePrefix(REQUESTED_LAYER_STATE)
        if (name.endsWith("}")) name = name.dropLast(1)
    }
    // getDebugString() appends these after the name, never before it.
    while (true) {
        val token = name.substringAfterLast(' ')
        if (token == "!handle") {
            name = name.substringBeforeLast(' ', "")
            continue
        }
        if (token.contains('=') && token.substringBefore('=').lowercase() in LISTING_ATTRIBUTES) {
            name = name.substringBeforeLast(' ', "")
            continue
        }
        break
    }
    return name.trim()
}

/**
 * The layer names worth asking for one app, in one round trip.
 *
 * The app's own layers come first. A game can draw into a surface that does not
 * carry its package name, so the newest layers of the list are kept behind them
 * as a fallback. `ActivityRecord{...}` entries are states of the window manager,
 * not buffers, and SurfaceFlinger has no frame times for them.
 */
fun layerNamesFor(
    layerList: String,
    foregroundPackage: String,
    ownLimit: Int = 8,
    newestLimit: Int = 4
): List<String> {
    val names = layerList.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { surfaceLayerName(it) }
        .filter { it.isNotBlank() }
        .filterNot { it.startsWith("ActivityRecord") }
        .distinct()
    if (names.isEmpty()) return emptyList()

    val pkg = foregroundPackage.trim()
    val own = if (pkg.isEmpty()) {
        emptyList()
    } else {
        names.filter { it.contains(pkg, ignoreCase = true) }
    }
    return (own.take(ownLimit) + names.takeLast(newestLimit)).distinct()
}

private const val REQUESTED_LAYER_STATE = "RequestedLayerState{"

/** The fields `getDebugString()` appends after the layer name. */
private val LISTING_ATTRIBUTES =
    setOf("parentid", "relativeparentid", "mirrorid", "z", "layerstack", "id", "hsr")

/** Orders layers by how recently they drew, keeping the rest as fallbacks. */
fun pickMostRecent(samples: List<LayerSample>): List<LayerSample> {
    if (samples.isEmpty()) return emptyList()
    return samples.sortedByDescending { it.presents.lastOrNull() ?: Long.MIN_VALUE }
}

/** What kind of surface this layer name describes. */
fun classifyLayer(name: String): LayerKind {
    val lower = name.lowercase()
    return when {
        // The system check comes first: a system surface is also a blast buffer
        // ("BLAST#101(com.android.systemui)/StatusBar#0"), and it must never be
        // mistaken for the surface of a game.
        lower.contains("systemui") ||
            lower.contains("navigationbar") || lower.contains("statusbar") ||
            lower.contains("colorlayer") || lower.contains("colorspace") ->
            LayerKind.SYSTEM
        lower.contains("surfaceview") || lower.contains("blast") || lower.contains("surfacetexture") ->
            LayerKind.SURFACE_VIEW
        lower.contains("window{") || lower.contains("activityrecord") ->
            LayerKind.WINDOW
        else -> LayerKind.UNKNOWN
    }
}

/**
 * How strongly the layer name points at the foreground package.
 *
 * The usual shape of a layer name is `SurfaceView[com.example.game/com.example.game.Main]`,
 * where the part before the slash is the package. A name that does not mention the
 * package at all tells us nothing, and a layer we cannot attribute is never used.
 */
fun packageEvidenceOf(name: String, foregroundPackage: String): PackageEvidence {
    if (foregroundPackage.isBlank()) return PackageEvidence.NONE
    val inner = BRACKET.find(name)?.groupValues?.getOrNull(1).orEmpty()
    if (inner.isNotBlank()) {
        val pkg = inner.substringBefore('/')
        if (pkg.equals(foregroundPackage, ignoreCase = true)) return PackageEvidence.EXACT
    }
    if (name.contains(foregroundPackage, ignoreCase = true)) return PackageEvidence.PARTIAL
    return PackageEvidence.NONE
}

/**
 * Finds the layer that belongs to the foreground app.
 *
 * A package association is mandatory. A layer that does not name the foreground package
 * is never used, however recently it drew: the alternative is to report the frame rate
 * of some other surface and call it the game's, which is the one thing this app must
 * never do.
 *
 * Among the layers that do belong to the app, the buffer queue a renderer draws into wins
 * over the app's window, and recency only breaks a tie between layers of the same kind.
 */
fun selectVerifiedTarget(
    samples: List<LayerSample>,
    foregroundPackage: String,
    allowUnattributed: Boolean = false
): TargetSelection {
    if (samples.isEmpty()) {
        return TargetSelection(
            target = null,
            reason = "SurfaceFlinger returned no layer for $foregroundPackage"
        )
    }

    val candidates = samples.map { sample ->
        LayerCandidate(
            sample = sample,
            kind = classifyLayer(sample.name),
            evidence = packageEvidenceOf(sample.name, foregroundPackage),
            lastPresentNs = sample.presents.lastOrNull() ?: Long.MIN_VALUE
        )
    }

    val rejected = mutableListOf<Pair<LayerCandidate, String>>()
    val matching = candidates.filter { candidate ->
        if (candidate.evidence == PackageEvidence.NONE) {
            rejected += candidate to "does not belong to $foregroundPackage"
            false
        } else {
            true
        }
    }

    if (matching.isEmpty()) {
        // A game can draw into a surface whose name does not carry the package
        // name at all. When nothing could be attributed, the layer that drew
        // most recently is the app in front - the phone draws one app at a
        // time, so the surface that is presenting right now is its surface.
        if (allowUnattributed) {
            val freshest = candidates
                .filter { it.kind != LayerKind.SYSTEM }
                .maxByOrNull { it.lastPresentNs }
            if (freshest != null && freshest.lastPresentNs != Long.MIN_VALUE) {
                candidates.filter { it !== freshest }.forEach { other ->
                    rejected += other to "drew less recently than ${freshest.sample.name}"
                }
                return TargetSelection(
                    target = freshest.sample,
                    reason = "layer ${freshest.sample.name} - the surface that drew last; " +
                        "its name does not carry the package name, so it is attributed by recency",
                    candidates = candidates,
                    rejected = rejected
                )
            }
        }
        return TargetSelection(
            target = null,
            reason = "No layer of $foregroundPackage is drawing; " +
                "${candidates.size} layer(s) were checked and none belong to it",
            candidates = candidates,
            rejected = rejected
        )
    }

    val usable = matching.filter { candidate ->
        if (candidate.kind == LayerKind.SYSTEM) {
            rejected += candidate to "is a system surface, not the app's own output"
            false
        } else {
            true
        }
    }

    if (usable.isEmpty()) {
        return TargetSelection(
            target = null,
            reason = "The only layers of $foregroundPackage are system surfaces",
            candidates = candidates,
            rejected = rejected
        )
    }

    val best = usable
        .sortedWith(
            compareByDescending<LayerCandidate> { kindPriority(it.kind) }
                .thenByDescending { it.lastPresentNs }
                .thenBy { it.sample.name }
        )
        .first()

    rejected += usable.drop(1).map { it to "drew less recently than ${best.sample.name}" }

    return TargetSelection(
        target = best.sample,
        reason = "verified layer ${best.sample.name} " +
            "(${best.kind.name.lowercase()}, ${best.evidence.name.lowercase()} package match)",
        candidates = candidates,
        rejected = rejected
    )
}

/** How much a kind of layer is preferred, highest first. */
private fun kindPriority(kind: LayerKind): Int = when (kind) {
    LayerKind.SURFACE_VIEW -> 3
    LayerKind.WINDOW -> 2
    LayerKind.UNKNOWN -> 1
    LayerKind.SYSTEM -> 0
}

/**
 * The counter of the display itself: the frames the screen presented.
 *
 * This is the number that answers "how fast is this screen", and it is not
 * the counter of any one layer. A layer of the home screen draws a handful
 * of frames and then stands still while the display keeps presenting.
 */
internal fun displayPresentedFrames(dump: String): Long? =
    Regex("""Total frames rendered:\s*(\d+)""").find(dump)
        ?.groupValues?.get(1)?.toLongOrNull()

/** The counter of the first layer in the dump: the frames that layer drew. */
internal fun layerPresentedFrames(dump: String): Long? =
    Regex("""totalFrames\s*=\s*(\d+)""").find(dump)
        ?.groupValues?.get(1)?.toLongOrNull()
