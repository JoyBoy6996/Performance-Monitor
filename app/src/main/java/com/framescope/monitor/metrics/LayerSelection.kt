package com.framescope.monitor.metrics

/** One SurfaceFlinger layer and the frame timestamps it produced. */
data class LayerSample(
    val name: String,
    val refreshPeriodNs: Long,
    val presents: List<Long>
)

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

        val presents = body.drop(1)
            .mapNotNull { row -> parseTimestampRow(row) }
            // A timestamp of zero is the placeholder for "this frame was never drawn".
            .filter { it > 0L }
            // The same timestamp can appear twice; a frame is one timestamp.
            .distinct()
            // Presentation timestamps arrive in order, but a dump taken while the
            // pipeline was catching up can be out of order.
            .sorted()

        if (presents.isNotEmpty()) {
            samples.add(LayerSample(block.name, refreshPeriodNs, presents))
        }
    }

    return samples
}

/**
 * Reads the presentation timestamp out of one row.
 *
 * SurfaceFlinger prints three columns: the desired vsync, the actual present time and
 * the time the buffer became ready. The present time is the second one. Some builds
 * print a single column, where that column is the present time.
 */
private fun parseTimestampRow(row: String): Long? {
    val columns = row.split(WHITESPACE)
    if (columns.isEmpty()) return null
    // Every column has to be a number, otherwise the columns cannot be trusted.
    if (columns.any { it.toLongOrNull() == null }) return null
    return when {
        columns.size >= 2 -> columns[1].toLong()
        else -> columns[0].toLong()
    }
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

/** Orders layers by how recently they drew, keeping the rest as fallbacks. */
fun pickMostRecent(samples: List<LayerSample>): List<LayerSample> {
    if (samples.isEmpty()) return emptyList()
    return samples.sortedByDescending { it.presents.lastOrNull() ?: Long.MIN_VALUE }
}

/** What kind of surface this layer name describes. */
fun classifyLayer(name: String): LayerKind {
    val lower = name.lowercase()
    return when {
        lower.contains("surfaceview") || lower.contains("blast") || lower.contains("surfacetexture") ->
            LayerKind.SURFACE_VIEW
        lower.contains("systemui") || lower.contains("systemui") ||
            lower.contains("navigationbar") || lower.contains("statusbar") ||
            lower.contains("colorlayer") || lower.contains("colorspace") ->
            LayerKind.SYSTEM
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
    foregroundPackage: String
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
