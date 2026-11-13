package com.framescope.monitor.metrics

object TimeStats {

    data class Reading(
        val layer: String,
        val frames: Long,
        val jankyFrames: Long,
        val missedVsync: Long
    )

    private val FRAMES_RE = Regex("Total frames rendered:\\s*(\\d+)", RegexOption.IGNORE_CASE)
    private val JANKY_RE = Regex("Janky frames:\\s*(\\d+)", RegexOption.IGNORE_CASE)
    private val MISSED_RE = Regex("Missed Vsync:\\s*(\\d+)", RegexOption.IGNORE_CASE)

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
            FRAMES_RE.find(line)?.let { frames = it.groupValues[1].toLongOrNull(); continue }
            JANKY_RE.find(line)?.let { janky = it.groupValues[1].toLongOrNull() ?: janky; continue }
            MISSED_RE.find(line)?.let { missed = it.groupValues[1].toLongOrNull() ?: missed; continue }

            val candidate = when {
                line.startsWith("Layer name:", ignoreCase = true) ->
                    line.substringAfter(":").trim()
                !raw.startsWith(" ") && !raw.startsWith("\t") &&
                    (line.contains("[") || line.contains("(") || line.contains("Surface") ||
                        line.startsWith("Window:") || line.startsWith("RequestedLayerState{")) ->
                    line
                else -> null
            }
            if (candidate != null && candidate.length > 3) {
                frames?.let { blocks += Triple(layer, it, janky to missed) }
                frames = null
                janky = 0L
                missed = 0L
                layer = candidate
            }
        }
        frames?.let { blocks += Triple(layer, it, janky to missed) }

        val pkgRegex = Regex("[\\[/\\s:]${Regex.escape(packageName)}[/\\s\\]#(:]")
        return blocks.filter {
            val layerName = it.first
            layerName.startsWith("$packageName/") ||
                layerName.contains("[$packageName/") ||
                layerName.contains("[$packageName]") ||
                layerName.contains(" $packageName/") ||
                pkgRegex.containsMatchIn(layerName)
        }.map { Reading(it.first, it.second, it.third.first, it.third.second) }
    }

    fun read(dump: String, packageName: String): Reading? = readAll(dump, packageName).firstOrNull()
}
