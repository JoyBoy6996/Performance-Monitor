package com.framescope.monitor.metrics

import com.framescope.monitor.core.ThreadLoad
import com.framescope.monitor.shell.Parsers

/**
 * The extra readings a Scene performance report shows that the original collectors
 * never stored: one load and one clock value per physical core, the memory (DDR)
 * clock and the busiest threads of the game process.
 *
 * Every number here comes from a real kernel counter. Nothing is inferred from the
 * frame rate, and a device that does not publish a node simply gets no value, which
 * the interface shows as "--" instead of a made-up zero.
 *
 * The readings are deltas, so the first poll of a run has no load values yet: a
 * cumulative counter cannot describe an interval until it has been seen twice.
 */
data class SceneReadings(
    /** Load of each core, in the same order as [cpuCoreFreqMHz]. Null means no reading. */
    val cpuCoreLoadPct: List<Float?> = emptyList(),
    /** Real kernel clock of each core, in MHz. */
    val cpuCoreFreqMHz: List<Int?> = emptyList(),
    /** DDR/devfreq memory clock, in MHz. */
    val ddrFreqMHz: Int? = null,
    /** Busiest threads of the measured app, largest average first. */
    val threadLoads: List<ThreadLoad> = emptyList()
)

class SceneCollectors {

    private var lastCoreStat: Map<Int, LongArray>? = null
    private var lastThreadTicks: Map<String, Long>? = null
    private var lastWallMs: Long? = null
    private var clockTicksPerSec: Int? = null

    /** Drops the previous readings so a new app or a new run starts clean. */
    fun reset() {
        lastCoreStat = null
        lastThreadTicks = null
        lastWallMs = null
    }

    /**
     * Parses one batch of shell output.
     *
     * @param coreDump per-core `/proc/stat` counters plus `CPUMAX=` capacity lines
     * @param coreFreqDump per-core `scaling_cur_freq` lines
     * @param ddrDump the memory devfreq dump
     * @param threadDump `THREAD=tid|comm|ticks` lines of the measured process
     * @param clockDump `getconf CLK_TCK` output
     * @param nowWallMs wall time of this poll, for the thread interval
     */
    fun parse(
        coreDump: String,
        coreFreqDump: String,
        ddrDump: String,
        threadDump: String,
        clockDump: String,
        nowWallMs: Long
    ): SceneReadings {
        if (clockTicksPerSec == null) {
            clockTicksPerSec = clockDump.lineSequence()
                .firstNotNullOfOrNull { Regex("\\d+").find(it)?.value?.toIntOrNull() }
                ?.takeIf { it in 1..10_000 }
        }

        val coreReadings = coreLoads(coreDump, coreFreqDump)
        val memoryClock = ddrFreq(ddrDump)
        val threads = threadLoads(threadDump, nowWallMs)
        return SceneReadings(coreReadings.first, coreReadings.second, memoryClock, threads)
    }

    /** One percentage and one clock per real core, ordered by core number. */
    private fun coreLoads(coreDump: String, coreFreqDump: String): Pair<List<Float?>, List<Int?>> {
        val current = Parsers.parsePerCoreCpuStat(coreDump)
        if (current.isEmpty()) return emptyList<Float?>() to emptyList<Int?>()
        val previous = lastCoreStat
        lastCoreStat = current

        val freqs = Parsers.parseCpuFreqs(coreFreqDump).mapValues { (_, khz) ->
            (khz / 1_000).takeIf { it > 0 }
        }
        val cores = (current.keys + freqs.keys).sorted()
        if (cores.isEmpty()) return emptyList<Float?>() to emptyList<Int?>()

        val loads = cores.map { core ->
            if (previous == null) {
                null
            } else {
                val now = current[core]
                val before = previous[core]
                if (now == null || before == null) {
                    null
                } else {
                    val totalDelta = now[0] - before[0]
                    val idleDelta = now[1] - before[1]
                    if (totalDelta <= 0L) {
                        null
                    } else {
                        ((1.0 - idleDelta.toDouble() / totalDelta.toDouble()) * 100.0)
                            .toFloat().coerceIn(0f, 100f)
                    }
                }
            }
        }
        return loads to cores.map { freqs[it] }
    }

    /** Memory clocks are reported in Hz by devfreq; Scene shows them in MHz. */
    private fun ddrFreq(dump: String): Int? =
        dump.lineSequence().firstNotNullOfOrNull { line ->
            Regex("(?:DDRFREQ|MEMFREQ)=(\\d+)").find(line)?.groupValues?.get(1)
                ?.toIntOrNull()?.let { hz -> (hz / 1_000).takeIf { it in 100..10_000 } }
        }

    /**
     * Thread load as a share of one core, using the same jiffy clock the kernel uses.
     *
     * A single thread cannot use more than one core, so a value above 100 is a
     * counter that wrapped or a clock mismatch and is discarded.
     */
    private fun threadLoads(dump: String, nowWallMs: Long): List<ThreadLoad> {
        val current = HashMap<String, Long>()
        val names = HashMap<String, String>()
        dump.lineSequence().forEach { line ->
            val parts = line.removePrefix("THREAD=").split("|")
            if (parts.size != 3) return@forEach
            val tid = parts[0].takeIf { it.isNotBlank() } ?: return@forEach
            val ticks = parts[2].trim().toLongOrNull()?.takeIf { it >= 0L } ?: return@forEach
            current[tid] = ticks
            names[tid] = parts[1].trim().take(40).ifBlank { tid }
        }
        if (current.isEmpty()) {
            lastThreadTicks = null
            return emptyList()
        }

        val previous = lastThreadTicks
        val wallMs = lastWallMs
        lastThreadTicks = current
        lastWallMs = nowWallMs
        // Without a previous poll there is no interval, and a poll that is not
        // newer than the last one has no interval either.
        if (previous == null || wallMs == null || nowWallMs <= wallMs) return emptyList()

        val elapsedSec = (nowWallMs - wallMs) / 1_000f
        val hz = clockTicksPerSec ?: 100
        val loads = current.mapNotNull { (tid, ticks) ->
            val before = previous[tid] ?: return@mapNotNull null
            val delta = ticks - before
            if (delta < 0L) return@mapNotNull null
            val percent = (delta * 100f / (hz * elapsedSec)).coerceIn(0f, 100f)
            ThreadLoad(names[tid] ?: tid, percent)
        }
        // Several kernel threads can share a name; keep the busiest entry per name.
        return loads.groupBy { it.name }
            .map { (name, rows) -> ThreadLoad(name, rows.maxOf { it.percent ?: 0f }) }
            .sortedByDescending { it.percent ?: 0f }
            .take(8)
    }
}
