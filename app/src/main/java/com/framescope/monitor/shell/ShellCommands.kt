package com.framescope.monitor.shell

import java.util.Locale

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

    /**
     * The app the user is looking at.
     *
     * The window manager alone is not enough. A floating window - a video in
     * picture-in-picture, a chat bubble - can hold the focus while a game is
     * drawn behind it, and the run then carries the name of the video app
     * instead of the name of the game. The activity dump knows more: it names
     * the task that is really resumed and says how that task is put on the
     * screen. Both are asked for and the parser decides which one is the app
     * in front.
     */
    fun foregroundPackage(): String =
        "dumpsys activity activities 2>/dev/null | " +
            "grep -m 12 -E 'topResumedActivity|mResumedActivity|mPinnedTask|" +
            "windowingMode=|mode=(fullscreen|pinned|freeform)' ; " +
            "dumpsys window 2>/dev/null | grep -m 2 -E 'mCurrentFocus|mFocusedApp'"

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
            "grep -m2 -E 'Total frames rendered|totalFrames'"

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

    fun cpuStat(): String = "cat /proc/stat 2>/dev/null | head -8"

    /**
     * The CPU load when `/proc/stat` cannot be read.
     *
     * Android 16 hides `/proc/stat` from the shell on some builds, and the
     * load then stays at zero even though the device is busy. The system
     * service still knows the load of every process, so it is asked instead:
     * `top` prints a sorted list and `dumpsys cpuinfo` prints the same numbers
     * in another shape. Both are real readings of the running system.
     */
    fun cpuFallback(): String =
        "top -b -n 1 -o %CPU,ARGS 2>/dev/null | head -40 ; " +
            "echo CPUSVC ; dumpsys cpuinfo 2>/dev/null | head -40"

    // The status line says whether the system is throttling; the rest are the sensors.
    fun thermal(): String =
        "dumpsys thermalservice 2>/dev/null | " +
            "grep -m 16 -E 'Thermal Status:|ThermalStatus:|Temperature\\{'"

    fun gpuInfo(): String =
        "dumpsys SurfaceFlinger 2>/dev/null | grep -m1 -E 'GLES:' ; " +
            "for f in /sys/class/devfreq/*gpu*/cur_freq /sys/class/devfreq/*gpu*/gpu_clock " +
            "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq /sys/class/kgsl/kgsl-3d0/gpu_clock " +
            "/sys/devices/platform/*.gpu/devfreq/cur_freq; do " +
            "[ -r \"\$f\" ] && echo \"GPUFREQPATH=\$f\" && " +
            "echo \"GPUFREQ=\$(cat \$f)\" && break; done"

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

    /**
     * Real GPU load, found at runtime instead of being guessed from the device name.
     *
     * The shell scans the kernel's own GPU interfaces: a ready percentage where one
     * exists (KGSL gpu_busy_percentage, Mali utilisation), otherwise busy/total ticks
     * (KGSL gpubusy, devfreq busy_time/total_time). Only a file that exists and holds
     * plausible numbers is used, so an absent counter stays "--" and never becomes 0.
     */
    fun gpuBusy(): String = listOf(
        "for f in /sys/class/kgsl/kgsl-*/gpu_busy_percentage " +
            "/sys/class/kgsl/kgsl-*/gpubusy " +
            "/sys/class/misc/mali0/device/utilisation " +
            "/sys/class/misc/mali0/device/utilization " +
            "/sys/devices/platform/*.gpu/utilisation " +
            "/sys/class/gpu*/utilization " +
            "/sys/kernel/gpu/gpu_busy; do",
        "  [ -r \"\$f\" ] || continue",
        "  v=\$(cat \"\$f\" 2>/dev/null)",
        "  p=\$(printf '%s' \"\$v\" | tr -cd '0-9.' 2>/dev/null)",
        "  case \"\$p\" in ''|*[!0-9.]*) continue ;; esac",
        "  p=\${p%%.*}",
        "  [ -n \"\$p\" ] && echo \"GPUPATH=\$f\" && echo \"GPUPCT=\$p\" && break",
        "done",
        "for d in /sys/class/devfreq/*gpu* /sys/class/kgsl/kgsl-*/devfreq " +
            "/sys/class/kgsl/kgsl-* /sys/devices/platform/*.gpu/devfreq; do",
        "  if [ -r \"\$d/busy_time\" ] && [ -r \"\$d/total_time\" ]; then",
        "    b=\$(cat \"\$d/busy_time\" 2>/dev/null)",
        "    t=\$(cat \"\$d/total_time\" 2>/dev/null)",
        "    case \"\$b:\$t\" in",
        "      *[!0-9:]*) ;;",
        "      *:*) [ -n \"\$b\" ] && [ -n \"\$t\" ] && " +
            "echo \"GPUPATH=\$d/busy_time\" && echo \"GPUBUSY=\$b:\$t\" && break ;;",
        "    esac",
        "  fi",
        "  if [ -r \"\$d/gpubusy\" ]; then",
        "    v=\$(cat \"\$d/gpubusy\" 2>/dev/null | tr '\\t' ' ')",
        "    case \"\$v\" in",
        "      *@*) b=\${v%@*}; t=\${v#*@}; echo \"GPUPATH=\$d/gpubusy\"; " +
            "echo \"GPUBUSY=\$b:\$t\"; break ;;",
        "      *\" \"*) b=\${v%% *}; t=\${v##* }; echo \"GPUPATH=\$d/gpubusy\"; " +
            "echo \"GPUBUSY=\$b:\$t\"; break ;;",
        "    esac",
        "  fi",
        "done"
    ).joinToString("\n")

    /**
     * Every thermal zone with its kernel name and raw value. `dumpsys thermalservice`
     * is often empty on newer builds, so the named sysfs zones are the real source.
     */
    fun thermalSysfs(): String =
        "for f in /sys/class/thermal/thermal_zone*/type; do " +
            "[ -r \"\$f\" ] || continue; " +
            "z=\${f%/type}; t=\$(cat \"\$f\" 2>/dev/null); " +
            "v=\$(cat \"\$z/temp\" 2>/dev/null); " +
            "echo \"TZLINE=\$z|\$t|\$v\"; done"

    /**
     * Identity, real shell uid and performance profile for a recorded run. All in the
     * same batched shell read as the rest of the telemetry, so it costs no extra tick.
     */
    fun telemetryIdentity(): String =
        "echo SHELLUID=\$(id -u 2>/dev/null); " +
            "echo SHELLUSER=\$(id -un 2>/dev/null); " +
            "echo PLATFORM=\$(getprop ro.board.platform 2>/dev/null); " +
            "echo PROFILE=\$(settings get system power_save_type 2>/dev/null); " +
            "echo GAMEMODE=\$(getprop sys.vivo.game.mode 2>/dev/null); " +
            "echo GAMEMODE2=\$(getprop persist.sys.game.mode 2>/dev/null)"

    /**
     * One-time session-start identity probe, run on the same privileged shell as
     * the per-second batch. It asks for the real uid, the kernel board platform,
     * the Vivo/iQOO game mode, the power profile, the advertised maximum core
     * clocks (for dynamic cluster grouping) and every thermal zone name.
     */
    fun telemetryInit(): String = listOf(
        telemetryIdentity(),
        "echo UID=\$(id -u 2>/dev/null)",
        "echo PLAT=\$(getprop ro.board.platform 2>/dev/null)",
        "echo VIVO_GAME=\$(getprop sys.vivo.game.mode 2>/dev/null)",
        "echo POWER_SAVE=\$(settings get system power_save_type 2>/dev/null)",
        "head -n 1 /sys/devices/system/cpu/cpu[0-9]*/cpufreq/cpuinfo_max_freq 2>/dev/null",
        cpuCoreDump()
    ).joinToString("\n")
}

/** Parsers for the shell output. */
object Parsers {

    private val PACKAGE_RE = Regex("([a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+)")
    private val THERMAL_NAME = Regex("mName=([^,}]+)")
    private val THERMAL_VALUE = Regex("mValue=([0-9.]+)")
    private val THERMAL_TYPE = Regex("mType=([0-9]+)")
    private val THERMAL_STATUS = Regex("Thermal\\s*Status\\s*:\\s*([0-6])", RegexOption.IGNORE_CASE)
    private val WHITESPACE = Regex("\\s+")

    /** The thermal type of the CPU sensor. */
    private const val THERMAL_TYPE_CPU = 0

    /** Marks the start of the CPU service answer inside one shell answer. */
    private const val CPU_SERVICE_MARKER = "CPUSVC"

    /** One row of the per-process load: "12.5 com.game" or "8.4% 2241/system_server". */
    private val CPU_ROW = Regex("^([0-9]+(?:\\.[0-9]+)?)%?\\s+(.+)$")

    /** The markers of a task that is drawn on top of another one. */
    private val FLOATING_MARKERS = listOf(
        "mPinnedTask",
        "mode=pinned",
        "windowingmode=3",
        "mode=freeform",
        "windowingmode=4"
    )

    /** Packages that are never the app in front. */
    private val FOREGROUND_NOISE = setOf(
        "android",
        "com.android.systemui",
        "com.qstats.fpsmonitor",
        "com.framescope.monitor"
    )

    /** Parts of a package name that mark an app that is never the app in front. */
    private val FOREGROUND_NOISE_MARKERS = listOf(
        "launcher",
        "homescreen",
        ".home",
        "inputmethod",
        ".ime",
        "keyboard",
        "systemui",
        "quicksettings"
    )

    /**
     * The app the user is looking at.
     *
     * A floating window can hold the focus while a game is drawn behind it: a
     * video in picture-in-picture is the common case, and it used to make a
     * run of a game carry the name of the video app. The dump names the task
     * that is really resumed, so that answer wins, and a task that is pinned
     * or freeform is never the answer.
     *
     * The order of the answers is the order of trust:
     *
     * 1. the resumed activity, which is the app that really has the screen;
     * 2. the focused window, which a floating window can steal;
     * 3. the fullscreen task, which is the only thing left when nothing named
     *    the app in front.
     */
    fun foregroundPackage(dump: String): String? {
        val floating = floatingPackages(dump)

        val resumed = dump.lineSequence()
            .filter { it.contains("topResumedActivity") || it.contains("mResumedActivity") }
            .mapNotNull { packageOf(it) }
            .firstOrNull { isForegroundCandidate(it, floating) }
        if (resumed != null) return resumed

        val focused = dump.lineSequence()
            .filter { it.contains("mCurrentFocus") || it.contains("mFocusedApp") }
            .mapNotNull { packageOf(it) }
            .firstOrNull { isForegroundCandidate(it, floating) }
        if (focused != null) return focused

        return dump.lineSequence()
            .filter {
                it.contains("mode=fullscreen", ignoreCase = true) ||
                    it.contains("windowingMode=1", ignoreCase = true)
            }
            .mapNotNull { packageOf(it) }
            .firstOrNull { isForegroundCandidate(it, floating) }
    }

    /**
     * The packages that only ever live in a floating window.
     *
     * A task that is pinned (picture in picture) or freeform is drawn on top
     * of something else, so it is never the app the user is looking at.
     */
    fun floatingPackages(dump: String): Set<String> = dump.lineSequence()
        .filter { line ->
            FLOATING_MARKERS.any { marker -> line.contains(marker, ignoreCase = true) }
        }
        .mapNotNull { packageOf(it) }
        .toSet()

    /**
     * True when the package can be the app the user is looking at.
     */
    fun isForegroundCandidate(packageName: String, floating: Set<String>): Boolean =
        packageName.isNotBlank() && packageName != "null" &&
            packageName !in floating && !isForegroundNoise(packageName)

    /**
     * True when the package is never the app the user is looking at.
     *
     * The system bars, the launcher, the keyboard and this app itself are
     * never the app a run is about.
     */
    fun isForegroundNoise(packageName: String): Boolean {
        val name = packageName.lowercase(Locale.US)
        if (name in FOREGROUND_NOISE) return true
        return FOREGROUND_NOISE_MARKERS.any { marker -> name.contains(marker) }
    }

    /** The first package-like name in a line of the dump. */
    private fun packageOf(line: String): String? =
        PACKAGE_RE.find(line)?.groupValues?.getOrNull(1)

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
     * The CPU temperature that the thermal service reports.
     *
     * The thermal service names its sensors with a type: 0 is the CPU. On a
     * build that hides the sysfs zones this is the only real chip
     * temperature left, and a temperature that was not measured is never
     * guessed.
     */
    fun parseThermalServiceCpuTemp(dump: String): Float? = dump.lineSequence()
        .filter { it.contains("Temperature{") }
        .mapNotNull { line ->
            val value = THERMAL_VALUE.find(line)?.groupValues?.get(1)?.toFloatOrNull()
                ?: return@mapNotNull null
            val type = THERMAL_TYPE.find(line)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@mapNotNull null
            if (type != THERMAL_TYPE_CPU) null else value
        }
        .firstOrNull()

    /**
     * The load of the device when `/proc/stat` could not be read.
     *
     * `top` prints the load of every process. The load of the device is the
     * sum of them, because every process is measured against one core, and
     * the sum is capped at 100%.
     */
    fun parseTopCpuUsage(dump: String): Float? {
        val rows = cpuRows(dump)
        if (rows.isEmpty()) return null
        val total = rows.values.sum()
        return total.coerceIn(0f, 100f).takeIf { it > 0f }
    }

    /**
     * The load of one process, in percent.
     *
     * This is how a game is found when the per-process counters of
     * `/proc/<pid>/task` cannot be read.
     */
    fun parseProcessCpuPercent(dump: String, packageName: String): Float? {
        if (packageName.isBlank()) return null
        return cpuRows(dump).entries
            .firstOrNull { it.key.contains(packageName) }
            ?.value
    }

    /**
     * The per-process load, from `top` or from the CPU service.
     *
     * Both are read in one shell call. `top` is the better answer - it is
     * measured at that moment - so it is used when it answered, and the
     * service is the second choice.
     */
    private fun cpuRows(dump: String): Map<String, Float> {
        val fromTop = cpuRowsOf(dump.substringBefore(CPU_SERVICE_MARKER))
        if (fromTop.isNotEmpty()) return fromTop
        return cpuRowsOf(dump.substringAfter(CPU_SERVICE_MARKER, ""))
    }

    /** The rows of one of the two shapes the load is printed in. */
    private fun cpuRowsOf(dump: String): Map<String, Float> {
        val rows = LinkedHashMap<String, Float>()
        dump.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            if (trimmed.startsWith("%") || trimmed.startsWith("Tasks")) return@forEach
            if (trimmed.startsWith("Load:") || trimmed.startsWith("CPU usage from")) return@forEach
            val match = CPU_ROW.find(trimmed) ?: return@forEach
            val percent = match.groupValues[1].toFloatOrNull() ?: return@forEach
            val name = match.groupValues[2].trim()
            if (name.isEmpty()) return@forEach
            rows[name] = (rows[name] ?: 0f) + percent
        }
        return rows
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

    /**
     * Real platform codename and the active performance profile.
     *
     * `getprop ro.board.platform` is the chip platform the kernel reports (for
     * example SUN). A profile is only claimed when a device actually publishes
     * one; otherwise the report falls back to "Balanced" instead of "--".
     */
    private fun lineValue(dump: String, key: String): String? = dump.lineSequence()
        .firstOrNull { it.trim().startsWith("$key=") }
        ?.substringAfter("=")?.trim()
        ?.takeIf { it.isNotBlank() && !it.equals("null", true) }

    fun parseShellUid(dump: String): String? = lineValue(dump, "SHELLUID")
        ?: lineValue(dump, "UID")

    data class ThermalZoneReading(val path: String, val type: String, val raw: Float?)

    /** Real named thermal zones found in sysfs, with their unparsed raw values. */
    fun parseThermalZones(dump: String): List<ThermalZoneReading> = dump.lineSequence()
        .mapNotNull { line ->
            val trimmed = line.trim()
            if (!trimmed.startsWith("TZLINE=")) return@mapNotNull null
            val cols = trimmed.removePrefix("TZLINE=").split("|", limit = 3)
            if (cols.size < 3) return@mapNotNull null
            ThermalZoneReading(cols[0], cols[1], cols[2].toFloatOrNull())
        }.toList()

    fun parseDeviceIdentity(dump: String): Pair<String?, String?> {
        val platform = (lineValue(dump, "PLATFORM") ?: lineValue(dump, "PLAT"))
            ?.uppercase(Locale.US)
        val game = listOfNotNull(
            lineValue(dump, "VIVO_GAME"), lineValue(dump, "GAMEMODE"),
            lineValue(dump, "GAMEMODE2")
        ).firstOrNull { it.isNotBlank() }
        val power = listOfNotNull(
            lineValue(dump, "PROFILE"), lineValue(dump, "POWER_SAVE")
        ).firstOrNull { it.isNotBlank() }
        val mode = listOfNotNull(game, power).joinToString(" ").lowercase(Locale.US)
        val namedMonster = listOf(
            "monster", "performance", "high", "ultra", "turbo", "esports"
        ).any { mode.contains(it) }
        // iQOO/Vivo expose game mode as a small integer. Any non-zero value is
        // an active performance mode; power_save_type alone is not a game mode.
        val activeGameMode = game?.trim()?.toIntOrNull()?.let { it > 0 } ?: false
        val profile = if (namedMonster || activeGameMode) "Monster" else "Balanced"
        return platform to profile
    }

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

    /** A kernel interface that publishes GPU load as a percentage directly. */
    fun parseGpuPercentDirect(dump: String): Float? {
        val line = dump.lineSequence().firstOrNull { it.startsWith("GPUPCT=") } ?: return null
        val raw = line.substringAfter("GPUPCT=").trim()
            .split(Regex("\\s+")).firstOrNull() ?: return null
        val value = raw.removeSuffix("%").toFloatOrNull() ?: return null
        return if (value in 0f..100f) value else null
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
