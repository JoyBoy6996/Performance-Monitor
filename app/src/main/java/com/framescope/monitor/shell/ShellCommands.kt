package com.framescope.monitor.shell

/** Helpers that build the shell snippets and parse their output. */
object ShellCommands {

    /** Runs several commands in one shell round trip, separated by a marker. */
    fun batch(vararg commands: String): String {
        val sep = "__FS_SEP__"
        return commands.joinToString("\n") { cmd ->
            "echo $sep\n{ $cmd ; } 2>/dev/null\n"
        }
    }

    const val SEPARATOR = "__FS_SEP__"

    /**
     * Splits a batched result back into one entry per command. Empty output is
     * kept so the index still matches the command order.
     */
    fun split(output: String): List<String> {
        if (output.isBlank()) return emptyList()
        val parts = output.split(SEPARATOR)
        if (parts.size <= 1) return emptyList()
        return parts.drop(1).map { it.trim() }
    }

    /** `dumpsys window` style foreground package detection. */
    fun foregroundPackage(): String =
        "{ dumpsys activity activities 2>/dev/null | grep -m1 -E 'topResumedActivity|mResumedActivity' ; " +
            "dumpsys window 2>/dev/null | grep -m1 -E 'mCurrentFocus|mFocusedApp' ; } | head -2"

    fun surfaceList(): String = "dumpsys SurfaceFlinger --list 2>/dev/null"

    /**
     * The latency of the layer that is on the screen.
     *
     * Without a layer name SurfaceFlinger answers with the layer that is
     * active - the app the user is looking at. A game draws into a surface
     * that is named after its engine, so the layer of the game cannot always
     * be found by its package name; this probe finds it anyway.
     */
    fun surfaceLatencyDefault(): String = "dumpsys SurfaceFlinger --latency 2>/dev/null"

    /** Fallback when SurfaceFlinger does not list layers: the window manager does. */
    fun surfaceWindows(): String =
        "dumpsys window windows 2>/dev/null | grep -E 'SurfaceView|Surface\\[' | head -20"

    /**
     * The latency of every layer of one app, in a single round trip.
     *
     * Asking the shell once per layer is slow, and a game can own a dozen
     * layers. One command that asks for all of them keeps the sample inside a
     * second even when the app has many surfaces.
     *
     * Every layer keeps only its newest frames. The latency dump is a rolling
     * window of about 128 rows per layer, and ten of those windows are bigger
     * than what a shell call may carry - the dump then arrives cut in half and
     * the frame rate of the game is lost. The frames that matter are the newest
     * ones, so the old ones are dropped in the shell, where they cost nothing.
     */
    fun surfaceLatencyOfNames(layers: List<String>): String {
        val usable = layers.map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        // Nothing to ask. An empty command would still cost a round trip and
        // look like a layer that has no frames.
        if (usable.isEmpty()) return "true"

        // `--latency` compares its argument with the layer name character for
        // character. The name is therefore passed in single quotes, as it is,
        // and nothing is added to it: the list entry for a layer is a
        // "RequestedLayerState{name parentId=..}" descriptor, not the name, and
        // asking with the descriptor answered with the refresh period only.
        return usable.joinToString("\n") { raw ->
            // Inside single quotes every other character is literal, so only the
            // quote itself has to go. Removing anything else would change the
            // name, and the name has to match.
            val name = raw.replace("'", "")
            // The rows are oldest first, so the newest frames are at the END of the
            // dump. A sweep of many layers keeps them to stay inside what a shell
            // call can carry; one layer is asked for its whole dump.
            "echo '@@@$name'\n" +
                "dumpsys SurfaceFlinger --latency '$name' 2>/dev/null | tail -n 60"
        }
    }


    /**
     * The latency dump of one single layer.
     *
     * Asking every layer on its own is slower than one command for all of them,
     * but one layer that hangs the shell cannot take the whole dump with it, and
     * every layer gets its own time.
     */
    fun surfaceLatencyOfLayer(layer: String): String {
        // The name is handed to the shell in single quotes. It used to be
        // wrapped in a second pair of quotes, so SurfaceFlinger was asked for
        // a layer whose name *contains* quotes, matched nothing, and answered
        // with an empty dump. The whole layer-by-layer probe never worked.
        val l = layer.replace("'", "")
        return "dumpsys SurfaceFlinger --latency '$l' 2>/dev/null; "
    }

    /**
     * The frame counters SurfaceFlinger keeps per layer.
     *
     * A game that renders straight into a buffer queue keeps no frame
     * timeline, so the per-frame timestamps are empty on builds that have
     * deprecated the frame tracker - and the counters are then the source
     * that still answers.
     */
    fun surfaceTimeStatsEnable(): String = "dumpsys SurfaceFlinger --timestats -enable 2>/dev/null"

    fun surfaceTimeStatsDump(): String = "dumpsys SurfaceFlinger --timestats -dump 2>/dev/null"

    /**
     * The device-wide presented-frame counter, and nothing else.
     *
     * A game's own timestats entry can stay frozen while it keeps presenting
     * frames; on this device the total at the top of the dump is the counter
     * that grows with the game. Only the first line is taken so the answer
     * stays small enough for every poll.
     */
    fun surfaceTotalFrames(): String =
        "dumpsys SurfaceFlinger --timestats -dump 2>/dev/null | " +
            "awk '/layerName =/{exit} /totalFrames/{print; exit}'"

    /**
     * The counters of one app, without the counters of every other app.
     *
     * The dump of the whole device holds a block for every layer it has, and a
     * phone with hundreds of layers answers with hundreds of kilobytes. That
     * answer does not fit in the time a measurement may take, and a dump that
     * arrives cut in half has lost the layers that come last - the ones that
     * are drawing. The blocks of the app in front are picked out in the shell,
     * where the text never travels.
     *
     * The end marker says that the whole answer arrived. A dump without it was
     * cut short, and a cut dump is refused rather than read.
     */
    fun surfaceTimeStatsDumpOf(packageName: String): String {
        val pkg = packageName.replace("'", "").replace("\\", "")
        return "dumpsys SurfaceFlinger --timestats -dump 2>/dev/null | " +
            "grep -B 6 -A 14 -F '$pkg'; " +
            "echo $TIMESTATS_END_MARKER"
    }

    /** The counters of every layer of the device. */
    fun surfaceTimeStatsDumpMarked(): String =
        "dumpsys SurfaceFlinger --timestats -dump 2>/dev/null; echo $TIMESTATS_END_MARKER"

    /** Says that the whole answer arrived. */
    const val TIMESTATS_END_MARKER = "__FS_TIMESTATS_END__"

    /**
     * One second of frame counters.
     *
     * The counters are dumped and cleared in the same command, so the frames in
     * the dump are exactly the frames since the last tick and nothing else. The
     * collection is switched on again right after, because clearing it also
     * stops it.
     */
    fun surfaceTimeStatsWindow(): String =
        "dumpsys SurfaceFlinger --timestats -dump -clear 2>/dev/null; " +
            "dumpsys SurfaceFlinger --timestats -enable 2>/dev/null"

    fun gfxInfo(packageName: String): String =
        "dumpsys gfxinfo $packageName framestats 2>/dev/null"

    fun battery(): String = "dumpsys battery 2>/dev/null"

    fun memInfo(): String = "cat /proc/meminfo 2>/dev/null | head -6"

    fun cpuStat(): String = "cat /proc/stat 2>/dev/null | head -8"

    // The status line says whether the system is throttling; the rest are the sensors.
    fun thermal(): String =
        "dumpsys thermalservice 2>/dev/null | " +
            "grep -m 16 -E 'Thermal Status:|ThermalStatus:|Temperature\\{'"

    fun gpuInfo(): String =
        "dumpsys SurfaceFlinger 2>/dev/null | grep -m1 -E 'GLES:' ; " +
            "for f in /sys/class/devfreq/*gpu*/cur_freq /sys/class/devfreq/*gpu*/gpu_clock " +
            "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq /sys/class/kgsl/kgsl-3d0/gpu_clock " +
            "/sys/devices/platform/*.gpu/devfreq/cur_freq; do " +
            "[ -r \"\$f\" ] && echo \"GPUFREQ=\$(cat \$f)\" && break; done"

    // The fastest core is the useful number on big.LITTLE chips. Reading only cpu0
    // would show a small efficiency core while the performance cores are busy.
    fun cpuFreq(): String =
        "f=\$(for f in /sys/devices/system/cpu/cpu[0-9]*/cpufreq/scaling_cur_freq; " +
            "do [ -r \"\$f\" ] && cat \"\$f\"; done | sort -nr | head -1); " +
            "[ -n \"\$f\" ] && echo \"CPUFREQ=\$f\""

    /**
     * Per-core counters and the real capacity of each core.
     *
     * Grouping by the reported maximum clock keeps the chart honest on any chip:
     * no fixed "little / big / prime" split is assumed.
     */
    fun cpuCoreDump(): String =
        "grep '^cpu[0-9]' /proc/stat 2>/dev/null; " +
            "for f in /sys/devices/system/cpu/cpu[0-9]*/cpufreq/cpuinfo_max_freq; do " +
            "[ -r \"\$f\" ] || continue; " +
            "n=\$(echo \"\$f\" | sed -E 's#.*/cpu([0-9]+)/.*#\\1#'); " +
            "echo \"CPUMAX=\$n:\$(cat \"\$f\")\"; " +
            "done"

    /** Current clock of each core, in kHz. */
    fun cpuCoreFreq(): String =
        "for f in /sys/devices/system/cpu/cpu[0-9]*/cpufreq/scaling_cur_freq; do " +
            "[ -r \"\$f\" ] || continue; " +
            "n=\$(echo \"\$f\" | sed -E 's#.*/cpu([0-9]+)/.*#\\1#'); " +
            "echo \"CPUFREQ=\$n:\$(cat \"\$f\")\"; " +
            "done"

    /**
     * Memory controller clock. Only real devfreq nodes are read; a device without
     * one answers nothing, which becomes "--" instead of a guessed number.
     */
    fun ddrFreq(): String =
        "for f in /sys/class/devfreq/*ddr*/cur_freq /sys/class/devfreq/*mem*/cur_freq " +
            "/sys/class/devfreq/*mc*/cur_freq /sys/kernel/debug/memfreq/current_freq; do " +
            "[ -r \"\$f\" ] && echo \"DDRFREQ=\$(cat \"\$f\")\" && break; done"

    /** The jiffy clock the kernel uses for `/proc` CPU time counters. */
    fun clockTicks(): String = "echo HZ=\$(getconf CLK_TCK 2>/dev/null || echo 100)"

    /**
     * CPU time of every thread of the measured app, in one shell round trip.
     *
     * `pidof` covers most devices; the cmdline scan covers games whose launcher
     * process is a different name. Thread names are kept exactly as the kernel
     * prints them so the report matches what the device actually runs.
     */
    fun threadStat(packageName: String): String {
        val pkg = packageName.replace("'", "").replace("\\", "").replace("$", "")
        if (pkg.isBlank()) return "true"
        // This is a raw string, so every shell '$' must be written as ${'$'}.
        return """
            p=${'$'}(pidof '$pkg' 2>/dev/null | awk '{print ${'$'}1}')
            if [ -z "${'$'}p" ]; then
              for d in /proc/[0-9]*; do
                grep -aqF '$pkg' "${'$'}d/cmdline" 2>/dev/null || continue
                p=${'$'}{d##*/}; break
              done
            fi
            if [ -n "${'$'}p" ]; then
              for t in /proc/${'$'}p/task/[0-9]*; do
                [ -r "${'$'}t/stat" ] || continue
                line=${'$'}(cat "${'$'}t/stat" 2>/dev/null) || continue
                tmp=${'$'}{line#*(}; comm=${'$'}{tmp%%)*}
                rest=${'$'}{line#*) }
                set -- ${'$'}rest
                [ ${'$'}# -ge 13 ] || continue
                echo "THREAD=${'$'}{t##*/}|${'$'}comm|${'$'}(( ${'$'}12 + ${'$'}13 ))"
              done
            fi
        """.trimIndent()
    }

    /** GPU busy time, only when the device publishes both counters. */
    fun gpuBusy(): String =
        "for d in /sys/class/devfreq/*gpu* /sys/class/kgsl/kgsl-3d0/devfreq " +
            "/sys/devices/platform/*.gpu/devfreq; do " +
            "[ -r \"\$d/busy_time\" ] && [ -r \"\$d/total_time\" ] && " +
            "echo \"GPUBUSY=\$(cat \"\$d/busy_time\"):\$(cat \"\$d/total_time\")\" && break; " +
            "done"

    fun displayInfo(): String =
        "dumpsys display 2>/dev/null | grep -m3 -E 'refreshRate|fps=' ; " +
            "dumpsys surfaceflinger 2>/dev/null | grep -m1 'refresh-rate'"

    fun deviceProps(): String =
        "getprop ro.product.model; getprop ro.board.platform; getprop ro.hardware; getprop ro.build.version.release"
}

/** Parsers for the shell output. */
object Parsers {

    private val PACKAGE_RE = Regex("([a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+)")
    private val THERMAL_NAME = Regex("mName=([^,}]+)")
    private val THERMAL_VALUE = Regex("mValue=([0-9.]+)")
    private val THERMAL_STATUS = Regex("Thermal\\s*Status\\s*:\\s*([0-6])", RegexOption.IGNORE_CASE)
    private val WHITESPACE = Regex("\\s+")

    fun foregroundPackage(dump: String): String? {
        val line = dump.lineSequence().firstOrNull {
            it.contains("topResumedActivity") || it.contains("mResumedActivity") ||
                it.contains("mCurrentFocus") || it.contains("mFocusedApp")
        } ?: return null
        return PACKAGE_RE.find(line)?.groupValues?.getOrNull(1)
    }

    /**
     * Parses `dumpsys SurfaceFlinger --latency`.
     *
     * Delegates to the pure-function core (parseLayerLatencies and computeFrameStats).
     */
    fun parseLatency(dump: String): Pair<Long, List<Long>> {
        val sample = com.framescope.monitor.metrics.parseLayerLatencies("@@@layer\n$dump").firstOrNull()
            ?: return 0L to emptyList()
        val deltas = sample.presents.zipWithNext { a, b -> b - a }.filter { it > 0L }
        val stats = com.framescope.monitor.metrics.computeFrameStats(deltas)
        if (stats == null) return sample.refreshPeriodNs to emptyList()
        return sample.refreshPeriodNs to sample.presents
    }

    /** Parses `dumpsys gfxinfo <pkg> framestats` frame completion timestamps. */
    private val FRAME_ROW = Regex("^\\d+,.*$")

    fun parseGfxFrameStats(dump: String): List<Long> {
        val lines = dump.lines()
        val headerIndex = lines.indexOfFirst { it.trim().startsWith("Flags,") }
        if (headerIndex < 0) return emptyList()
        val header = lines[headerIndex].split(",").map { it.trim() }
        val completedIndex = header.indexOfFirst { it.equals("FrameCompleted", true) }
        if (completedIndex < 0) return emptyList()
        val out = ArrayList<Long>()
        for (i in headerIndex + 1 until lines.size) {
            val line = lines[i].trim()
            if (!FRAME_ROW.matches(line)) {
                if (out.isNotEmpty()) break
                continue
            }
            val cols = line.split(",")
            if (cols.size <= completedIndex) continue
            cols[completedIndex].trim().toLongOrNull()?.let { if (it > 0) out.add(it) }
        }
        return out
    }

    fun parseBattery(dump: String): Map<String, String> {
        val map = HashMap<String, String>()
        dump.lineSequence().forEach { line ->
            val parts = line.split(":", limit = 2)
            if (parts.size == 2) map[parts[0].trim().lowercase()] = parts[1].trim()
        }
        return map
    }

    fun parseMemInfo(dump: String): Pair<Long, Long> {
        var total = 0L
        var available = 0L
        dump.lineSequence().forEach { line ->
            val parts = line.split(Regex("\\s+"))
            if (parts.size >= 2) {
                val value = parts[1].toLongOrNull() ?: return@forEach
                when (parts[0]) {
                    "MemTotal:" -> total = value * 1024
                    "MemAvailable:" -> available = value * 1024
                }
            }
        }
        return total to available
    }

    /** Parses the aggregate cpu line of `/proc/stat`. */
    fun parseCpuStat(dump: String): LongArray? {
        val line = dump.lineSequence().firstOrNull { it.startsWith("cpu ") } ?: return null
        val parts = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (parts.size < 4) return null
        val total = parts.sum()
        val idle = parts[3] + parts.getOrElse(4) { 0L }
        return longArrayOf(total, idle)
    }

    /**
     * Parses `dumpsys thermalservice` temperature values.
     *
     * The order of `mValue` and `mName` differs between Android versions, so
     * both fields are looked up on their own.
     */
    fun parseThermals(dump: String): Map<String, Float> {
        val map = HashMap<String, Float>()
        dump.lineSequence()
            .filter { it.contains("Temperature{") || it.contains("mValue=") }
            .forEach { line ->
                val name = THERMAL_NAME.find(line)?.groupValues?.get(1)?.trim() ?: return@forEach
                val value = THERMAL_VALUE.find(line)?.groupValues?.get(1)?.toFloatOrNull()
                    ?: return@forEach
                map[name] = value
            }
        return map
    }

    /**
     * Android's thermal mitigation level: 0 none, 1 light, 2 moderate, 3 severe.
     * Null when the device does not print a status line - never guess a level.
     */
    fun parseThermalStatus(dump: String): Int? =
        THERMAL_STATUS.find(dump)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..6 }

    fun parseGpuName(dump: String): String? {
        val line = dump.lineSequence().firstOrNull { it.contains("GLES:") } ?: return null
        return line.substringAfter("GLES:").trim().takeIf { it.isNotBlank() }
    }

    fun parseGpuFreq(dump: String): Int? =
        dump.lineSequence().firstOrNull { it.startsWith("GPUFREQ=") }
            ?.substringAfter("=")?.trim()?.toIntOrNull()

    fun parseCpuFreq(dump: String): Int? =
        dump.lineSequence().firstOrNull { it.startsWith("CPUFREQ=") }
            ?.substringAfter("=")?.trim()?.toIntOrNull()

    /** Aggregate counters of every core in `/proc/stat`, keyed by core number. */
    fun parsePerCoreCpuStat(dump: String): Map<Int, LongArray> {
        val out = HashMap<Int, LongArray>()
        dump.lineSequence().forEach { line ->
            val core = Regex("^cpu(\\d+)\\s+(.*)$").find(line.trim()) ?: return@forEach
            val numbers = core.groupValues[2].trim().split(WHITESPACE)
                .mapNotNull { it.toLongOrNull() }
            if (numbers.size < 4) return@forEach
            out[core.groupValues[1].toInt()] = longArrayOf(
                numbers.sum(),
                numbers[3] + numbers.getOrElse(4) { 0L }
            )
        }
        return out
    }

    /** Maximum clock of each core, in kHz. Only real nodes are returned. */
    fun parseCpuMaxFreqs(dump: String): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        dump.lineSequence()
            .filter { it.startsWith("CPUMAX=") }
            .forEach { line ->
                val pair = line.substringAfter("CPUMAX=").trim().split(":")
                if (pair.size == 2) {
                    val core = pair[0].toIntOrNull()
                    val khz = pair[1].toIntOrNull()
                    if (core != null && khz != null && khz > 0) out[core] = khz
                }
            }
        return out
    }

    /** Current clock of each core, in kHz, keyed by core number. */
    fun parseCpuFreqs(dump: String): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        dump.lineSequence()
            .filter { it.startsWith("CPUFREQ=") }
            .forEach { line ->
                val pair = line.substringAfter("CPUFREQ=").trim().split(":")
                if (pair.size == 2) {
                    val core = pair[0].toIntOrNull()
                    val khz = pair[1].toIntOrNull()
                    if (core != null && khz != null && khz > 0) out[core] = khz
                }
            }
        return out
    }

    /** Cumulative GPU busy/total ticks, when the device exposes them. */
    fun parseGpuBusy(dump: String): Pair<Long, Long>? {
        val line = dump.lineSequence().firstOrNull { it.startsWith("GPUBUSY=") } ?: return null
        val pair = line.substringAfter("GPUBUSY=").trim().split(":")
        if (pair.size != 2) return null
        val busy = pair[0].toLongOrNull() ?: return null
        val total = pair[1].toLongOrNull() ?: return null
        return if (total > 0L && busy >= 0L) busy to total else null
    }

    fun parseRefreshRate(dump: String): Float? {
        val re = Regex("refreshRate=([0-9.]+)")
        re.find(dump)?.let { return it.groupValues[1].toFloatOrNull() }
        val re2 = Regex("([0-9.]+)\\s*fps")
        re2.find(dump)?.let { return it.groupValues[1].toFloatOrNull() }
        return null
    }
}
