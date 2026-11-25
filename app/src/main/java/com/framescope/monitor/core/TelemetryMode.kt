package com.framescope.monitor.core

/**
 * The line that says how a run was measured.
 *
 * The privileged shell reads the hardware as the device itself reads it, and
 * the app's own process gets only what the system lets it read. Both are
 * real ways of measuring, and the line says which one was used - so the
 * Shell Logs of a new run never says that nothing was captured.
 */
fun telemetryModeLine(
    shizukuActive: Boolean,
    platform: String? = null,
    clusters: List<String> = emptyList()
): String = if (shizukuActive) {
    "Mode: Shizuku (uid=2000)"
} else {
    "Mode: Basic | Platform: ${platform?.takeIf { it.isNotBlank() } ?: "--"} | " +
        "Clusters: ${clusters.filter { it.isNotBlank() }.joinToString(", ").ifBlank { "--" }}"
}
