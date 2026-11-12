package com.framescope.monitor.metrics

/** One SurfaceFlinger layer and the frame timestamps it produced. */
data class LayerSample(
    val name: String,
    val refreshPeriodNs: Long,
    val presents: List<Long>
)

/** Splits the multi-layer dump into one sample per layer. */
fun parseLayerLatencies(dump: String): List<LayerSample> {
    if (dump.isBlank()) return emptyList()

    val blocks = if (dump.contains("@@@")) {
        dump.split("@@@").filter { it.isNotBlank() }
    } else {
        listOf(dump)
    }

    val samples = mutableListOf<LayerSample>()
    for (block in blocks) {
        val lines = block.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) continue

        val hasName = dump.contains("@@@")
        val name = if (hasName) lines[0] else ""
        val remainingLines = if (hasName) lines.drop(1) else lines

        if (remainingLines.isEmpty()) continue

        val refreshPeriodNs = remainingLines[0].split("\\s+".toRegex()).firstOrNull()?.toLongOrNull() ?: 0L
        val dataRows = remainingLines.drop(1)
            .map { row -> row.split("\\s+".toRegex()).mapNotNull { it.toLongOrNull() } }
            .filter { it.isNotEmpty() }

        if (dataRows.isEmpty()) continue

        // Skip leading placeholder row if all zeros (SurfaceFlinger's "frame that was never drawn")
        val validRows = if (dataRows.first().all { it == 0L }) dataRows.drop(1) else dataRows
        if (validRows.isEmpty()) continue

        // Present time is typically column 1 (actual present time), or column 0 if only 1 column
        val presents = validRows.mapNotNull { cols ->
            when {
                cols.size >= 2 -> cols[1]
                cols.isNotEmpty() -> cols[0]
                else -> null
            }
        }.filter { it > 0L }

        // Skip blocks with no frames at all
        if (presents.isNotEmpty()) {
            samples.add(LayerSample(name, refreshPeriodNs, presents))
        }
    }

    return samples
}

/** Orders layers by how recently they drew, keeping the rest as fallbacks. */
fun pickMostRecent(samples: List<LayerSample>): List<LayerSample> {
    if (samples.isEmpty()) return emptyList()
    return samples.sortedByDescending { it.presents.lastOrNull() ?: Long.MIN_VALUE }
}
