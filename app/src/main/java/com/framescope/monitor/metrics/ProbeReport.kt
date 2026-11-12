package com.framescope.monitor.metrics

import java.util.Locale

/** A human-readable proof of what the sampler saw and why it rejected it. */
fun buildProbeReport(packageName: String, dump: String): String {
    if (dump.isBlank()) {
        return "app: $packageName\nno layer answered"
    }

    val blocks = if (dump.contains("@@@")) {
        dump.split("@@@").filter { it.isNotBlank() }
    } else {
        emptyList()
    }

    if (blocks.isEmpty()) {
        return "app: $packageName\nno layer answered"
    }

    val sb = StringBuilder()
    sb.appendLine("app: $packageName")

    for (block in blocks) {
        val lines = block.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) continue

        val layerName = lines[0]
        sb.appendLine("layer: $layerName")

        val remaining = lines.drop(1)
        if (remaining.isEmpty()) {
            sb.appendLine("no frames")
            continue
        }

        val dataRows = remaining.drop(1) // Skip refresh period
        if (dataRows.isEmpty()) {
            sb.appendLine("no frames")
            continue
        }

        // Echo raw data rows as markdown rows (strictly one line per data row starting with "| ")
        for (row in dataRows) {
            sb.appendLine("| $row |")
        }

        // Check if rows contain valid frame timestamps
        val parsedRows = dataRows.map { row ->
            row.split("\\s+".toRegex()).mapNotNull { it.toLongOrNull() }
        }.filter { it.isNotEmpty() }

        val validRows = if (parsedRows.firstOrNull()?.all { it == 0L } == true) parsedRows.drop(1) else parsedRows
        val presents = validRows.mapNotNull { cols ->
            when {
                cols.size >= 2 -> cols[1]
                cols.isNotEmpty() -> cols[0]
                else -> null
            }
        }.filter { it > 0L }

        if (presents.isEmpty()) {
            sb.appendLine("no frames")
        } else {
            val deltas = presents.zipWithNext { a, b -> b - a }.filter { it > 0L }
            if (deltas.isNotEmpty()) {
                val medianNs = deltas.sorted()[deltas.size / 2]
                val medianMs = medianNs / 1_000_000.0
                sb.appendLine(String.format(Locale.US, "ok (%.1f ms)", medianMs))
            } else {
                sb.appendLine("ok")
            }
        }
    }

    return sb.toString().trimEnd()
}

/** Keeps the newest report per app, capped, oldest evicted first. */
fun retainReport(map: Map<String, String>, packageName: String, report: String): Map<String, String> {
    val linked = LinkedHashMap(map)
    linked.remove(packageName)
    linked[packageName] = report
    while (linked.size > 4) {
        val oldestKey = linked.keys.first()
        linked.remove(oldestKey)
    }
    return linked
}
