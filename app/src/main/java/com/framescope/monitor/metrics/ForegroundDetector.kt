package com.framescope.monitor.metrics

import android.Manifest
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import com.framescope.monitor.shell.Parsers
import com.framescope.monitor.shell.ShellCommands
import com.framescope.monitor.shell.ShellHub

/**
 * Works out which app is in front of the user.
 *
 * This used to live inside the frame sampler, which meant a phone without Shizuku could
 * not even find out which app it was supposed to be measuring. Foreground detection is a
 * separate job with its own sources and its own limits:
 *
 * - A privileged query of the window manager is exact.
 * - Usage access is an approximation: it reports what was used recently, so it can name
 *   the wrong app for a moment after a switch. It is marked as not confident so the rest
 *   of the app can say so.
 */
class ForegroundDetector(
    private val hub: ShellHub,
    var context: Context? = null
) {

    /** Where the answer came from. */
    enum class Source {
        /** The window manager, through a privileged shell. Exact. */
        PRIVILEGED,

        /** Usage access. Approximate. */
        USAGE_STATS,

        /** Nothing could be determined. */
        NONE
    }

    /** The result of one detection. */
    data class Detection(
        val packageName: String?,
        val source: Source,
        /** True when the answer is known to be the app in front right now. */
        val confident: Boolean,
        /** Why the answer is missing or uncertain. */
        val detail: String? = null
    ) {
        val known: Boolean get() = !packageName.isNullOrBlank()
    }

    /**
     * Finds the app in front of the user.
     *
     * The privileged query is tried first because it is exact. Usage access is only used
     * when the user actually granted it.
     */
    suspend fun detect(): Detection {
        val privileged = detectPrivileged()
        val usage = detectWithUsageStats()

        val chosen = chooseForeground(privileged, usage.detection, usage.lastUsedAt)
        if (chosen.known) return chosen

        return Detection(
            packageName = null,
            source = Source.NONE,
            confident = false,
            detail = privileged.detail
                ?: "No foreground app could be determined. Shizuku gives an exact answer, usage access an approximate one."
        )
    }

    /** Reads the resumed activity out of the window manager. */
    /**
     * The text the last query answered with.
     *
     * It is kept because a wrong answer to "which app is in front" is the
     * one thing that cannot be seen in a number, only in the text.
     */
    @Volatile
    var lastOutput: String = ""
        private set

    private suspend fun detectPrivileged(): Detection {
        val out = hub.execOrNull(ShellCommands.foregroundPackage(), 2_000)?.out
        lastOutput = out.orEmpty()
        if (!out.isNullOrBlank()) {
            val detected = Parsers.foregroundPackage(out)
            if (!detected.isNullOrBlank() && detected != "null") {
                return Detection(
                    packageName = detected,
                    source = Source.PRIVILEGED,
                    confident = true
                )
            }
        }
        return Detection(
            packageName = null,
            source = Source.NONE,
            confident = false,
            detail = "Shizuku is not connected, so the foreground app is unknown"
        )
    }

    /**
     * Asks the usage stats service which app was used last.
     *
     * The resolution of this source is coarse and it lags behind a real switch, so the
     * answer is marked as not confident.
     */
    private suspend fun detectWithUsageStats(): UsageReading {
        val ctx = context ?: return UsageReading(
            Detection(
                packageName = null,
                source = Source.NONE,
                confident = false,
                detail = "No context for usage access"
            ),
            emptyMap()
        )

        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return UsageReading(
                Detection(
                    packageName = null,
                    source = Source.NONE,
                    confident = false,
                    detail = "Usage stats service is not available"
                ),
                emptyMap()
            )

        val end = System.currentTimeMillis()
        // The window is five minutes, not five seconds. An app that is in front
        // for a long time - a game - reports its last use long after it was
        // opened, and a short window misses it completely.
        val begin = end - 300_000L
        // The query is the truth: it returns nothing when usage access is missing and
        // it also returns nothing when the user has not opened anything for a while.
        val stats = runCatching {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, begin, end)
        }.getOrNull()

        val used = stats
            ?.filter { it.lastTimeUsed > begin && it.totalTimeInForeground > 0L }
            ?: emptyList()
        val lastUsedAt = used.associate { it.packageName to it.lastTimeUsed }
        val mostRecent = used.maxByOrNull { it.lastTimeUsed }
        if (mostRecent != null) {
            return UsageReading(
                Detection(
                    packageName = mostRecent.packageName,
                    source = Source.USAGE_STATS,
                    confident = false,
                    detail = "Detected through usage access, which can lag behind a switch"
                ),
                lastUsedAt
            )
        }

        return UsageReading(
            Detection(
                packageName = null,
                source = Source.NONE,
                confident = false,
                detail = if (hasUsageAccess(ctx)) {
                    "Usage access has no recent app to report"
                } else {
                    "Usage access is not granted, so the foreground app is unknown"
                }
            ),
            lastUsedAt
        )
    }

    /**
     * The answer of the usage access, with when every app was last in front.
     */
    private data class UsageReading(
        val detection: Detection,
        /** When each app was last in front of the user. */
        val lastUsedAt: Map<String, Long>
    )

    /**
     * Whether the system says that usage access was granted.
     *
     * Only used to describe the situation, never to decide it: the query above is what
     * decides whether there is an app to report. The check goes through the package
     * manager instead of the app ops service, which keeps it cheap and keeps the answer
     * honest on builds that do not surface the op through the permission.
     */
    private fun hasUsageAccess(context: Context): Boolean = try {
        context.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) ==
            PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) {
        false
    }

    companion object {
        /**
         * How much more recent an app has to be before the window manager's
         * answer is treated as stale.
         *
         * The usage access is coarse, so a small difference means nothing. A
         * clear difference means the window manager is naming something that is
         * on the screen but not in front.
         */
        const val STALE_ANSWER_MS = 1_500L
    }
}

/**
 * Picks the answer that names the app the user is looking at.
 *
 * The window manager can name a window that is on the screen but is not in
 * front: an overlay of another app, a launcher that keeps the focus while a
 * game is drawn over it. The usage access knows which app was in front a
 * moment ago. When the two disagree and one of them is clearly more recent,
 * that one is the answer.
 *
 * A small difference means nothing - the usage access is coarse - and the
 * window manager wins, because it is exact.
 */
fun chooseForeground(
    privileged: ForegroundDetector.Detection,
    usage: ForegroundDetector.Detection,
    lastUsedAt: Map<String, Long>
): ForegroundDetector.Detection {
    if (!privileged.known) return usage
    if (!usage.known) return privileged
    if (usage.packageName == privileged.packageName) return privileged

    val privilegedUsedAt = lastUsedAt[privileged.packageName]
    val usageUsedAt = lastUsedAt[usage.packageName]
    if (privilegedUsedAt == null || usageUsedAt == null) return privileged
    if (usageUsedAt <= privilegedUsedAt + ForegroundDetector.STALE_ANSWER_MS) return privileged

    return usage.copy(
        confident = false,
        detail = "The window manager named ${privileged.packageName}, but " +
            "${usage.packageName} was in front more recently"
    )
}
