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
     * layers. One command that walks the list keeps the sample inside a
     * second even when the app has many surfaces.
     *
     * Every layer keeps only its newest frames. The latency dump is a rolling
     * window of about 128 rows per layer, and ten of those windows are bigger
     * than what a shell call may carry - the dump then arrives cut in half and
     * the frame rate of the game is lost. The frames that matter are the newest
     * ones, so the old ones are dropped in the shell, where they cost nothing.
     */
    fun surfaceLatencyOfLayers(packageName: String, fallback: Boolean = false): String {
        val pkg = packageName.replace("'", "")
        val layers = if (fallback) {
            // No layer carries the package name: the newest layers are checked
            // instead, because a game often renders into a surface of its own.
            "dumpsys SurfaceFlinger --list 2>/dev/null | tail -12"
        } else {
            // Every layer of the app is asked. A game owns a dozen of them and
            // the one that draws is not always the newest one, so nothing is
            // left out here.
            "dumpsys SurfaceFlinger --list 2>/dev/null | grep -F '$pkg'"
        }
        // The list has to be walked line by line. A `for` loop over `$(...)` splits
        // every line at its spaces, and a SurfaceFlinger layer name contains spaces:
        // "RequestedLayerState{ActivityRecord{219735033 u0 t4356 d0} 11-13#0}".
        // Every piece of it is a layer that does not exist, so the dump comes back
        // empty and the frame rate of that layer is never measured.
        return "{ $layers | while IFS= read -r L; do " +
            "[ -z \"\$L\" ] && continue; " +
            "echo \"@@@\$L\"; " +
            "${latencyOf("\$L")}" +
            "B=\${L%%#*}; if [ \"\$B\" != \"\$L\" ]; then echo \"@@@\$B\"; " +
            "${latencyOf("\$B")}" +
            "fi; done; }"
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
     * The latency of one layer, when it is a variable of the walk above.
     *
     * The rows are oldest first, so the newest frames are at the END of the
     * dump. Keeping the first rows - which this used to do - kept the oldest
     * frames of the layer, the ones that are no longer being presented, and
     * the rate was read from a stale window. A sweep of many layers keeps the
     * newest rows to stay inside what a shell call can carry.
     *
     * Nothing but the tools that are on every phone is used here. A phone has
     * no awk, and a command that is not there takes the whole answer with it.
     */
    private fun latencyOf(layer: String): String =
        "dumpsys SurfaceFlinger --latency \"$layer\" 2>/dev/null | tail -n 60; "

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

    fun thermal(): String = "dumpsys thermalservice 2>/dev/null | grep -m 12 -E 'Temperature\\{'"

    fun gpuInfo(): String =
        "dumpsys SurfaceFlinger 2>/dev/null | grep -m1 -E 'GLES:' ; " +
            "for f in /sys/class/devfreq/*gpu*/cur_freq /sys/class/devfreq/*gpu*/gpu_clock " +
            "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq /sys/class/kgsl/kgsl-3d0/gpu_clock " +
            "/sys/devices/platform/*.gpu/devfreq/cur_freq; do " +
            "[ -r \"\$f\" ] && echo \"GPUFREQ=\$(cat \$f)\" && break; done"

    fun cpuFreq(): String =
        "for f in /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq " +
            "/sys/devices/system/cpu/cpufreq/scaling_cur_freq; do " +
            "[ -r \"\$f\" ] && echo \"CPUFREQ=\$(cat \$f)\" && break; done"

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

    fun parseRefreshRate(dump: String): Float? {
        val re = Regex("refreshRate=([0-9.]+)")
        re.find(dump)?.let { return it.groupValues[1].toFloatOrNull() }
        val re2 = Regex("([0-9.]+)\\s*fps")
        re2.find(dump)?.let { return it.groupValues[1].toFloatOrNull() }
        return null
    }
}
