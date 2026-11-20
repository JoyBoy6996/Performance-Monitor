package com.framescope.monitor.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock

/**
 * The evidence needed to explain a wrong frame rate reading.
 *
 * It walks the same path the sampler takes - the foreground app, the
 * SurfaceFlinger layers, the latency dump of the app's own layer - so the
 * output shows exactly what the app saw and what it could not make sense of.
 */
object Diagnostics {

    val command: String = listOf(
        "echo '--- foreground app ---'",
        "dumpsys activity activities 2>/dev/null | grep -m1 -E 'topResumedActivity|mResumedActivity'",
        "echo '--- surfaceflinger layers ---'",
        "dumpsys SurfaceFlinger --list 2>/dev/null | head -60",
        "echo '--- latency of the foreground layer ---'",
        "PKG=$(dumpsys activity activities 2>/dev/null | grep -m1 -oE '[a-zA-Z0-9_]+\\.[a-zA-Z0-9_]+/' | cut -d/ -f1); " +
            "echo \"package=\$PKG\"; " +
            "L=$(dumpsys SurfaceFlinger --list 2>/dev/null | grep -m1 \"\$PKG\"); " +
            "echo \"layer=\$L\"; " +
            "if [ -n \"\$L\" ]; then dumpsys SurfaceFlinger --latency \"\$L\" 2>/dev/null | head -8; fi",
        "echo '--- latency without a layer name ---'",
        "dumpsys SurfaceFlinger --latency 2>/dev/null | head -5",
        "echo '--- gfxinfo frame stats ---'",
        "if [ -n \"\$PKG\" ]; then dumpsys gfxinfo \"\$PKG\" framestats 2>/dev/null | " +
            "grep -m4 -E 'Flags,|FrameCompleted' | cut -c1-170; fi",
        "echo '--- privileged identity ---'",
        "id 2>/dev/null || whoami 2>/dev/null"
    ).joinToString("; ")

    /**
     * The raw evidence of one app's layers.
     *
     * This asks SurfaceFlinger the same questions the sampler asks, but it keeps
     * the answers as they are. The identity shows who is really running the
     * command, the layer list shows what SurfaceFlinger calls the layers, and
     * the latency dump of the app's own layers shows whether the frames are
     * there at all.
     */
    fun probeCommand(
        packageName: String?,
        measuredLayer: String? = null,
        debugText: String = ""
    ): String {
        val pkg = (packageName ?: "").replace("'", "").replace("\"", "")
        val app = if (pkg.isBlank()) "" else pkg
        val layer = (measuredLayer ?: "").replace("'", "")
        val atMs = SystemClock.uptimeMillis()
        return listOf(
            // The package and uptime of the capture itself. The button and the
            // counters below can look right while the app underneath changed,
            // so this line says exactly which app the raw probe belongs to.
            "echo 'captured_from=$app at=$atMs'",
            // The last five polled readings. Each line: `<uptime-ms> <FPS>`.
            // The newest is last. These are what the overlay showed, including
            // polls that said 0 or read as the panel's refresh rate, so the
            // real rate and the misleading ones can be compared by hand after
            // the game closes.
            if (debugText.isNotBlank()) {
                // Lines are `<uptime-ms> <FPS>`; newest is last.
                "echo '--- last 5 polled FPS readings (engine.debugFrameLog, newest last) ---'; " +
                    "echo \"$debugText\""
            } else {
                ""
            }.trim(),
            // The layer the app is measuring right now comes first. `--latency`
            // answers to the exact layer name and to nothing else, so the row
            // count of this block is the whole question: about 127 rows when the
            // name is right, one line - the refresh period - when it is not.
            if (layer.isBlank()) {
                "echo '--- the layer that was measured: none yet ---'"
            } else {
                "echo '--- the layer that was measured ---'; " +
                    "echo 'layer=[$layer]'; " +
                    "echo \"rows=\$(dumpsys SurfaceFlinger --latency '$layer' 2>/dev/null | wc -l)\"; " +
                    "dumpsys SurfaceFlinger --latency '$layer' 2>/dev/null | tail -4"
            },
            // The counters come first: they are the text that decides the
            // frame rate of a game, and they are the part a screenshot has to
            // show.
            "echo '--- counters of $app ---'",
            "dumpsys SurfaceFlinger --timestats -dump 2>/dev/null | " +
                "grep -B 6 -A 14 -F '$app' | head -50",
            "echo '--- counters of every layer (first blocks) ---'",
            "dumpsys SurfaceFlinger --timestats -dump 2>/dev/null | head -24",
            "echo '--- who is running this ---'",
            "id 2>/dev/null",
            "dumpsys SurfaceFlinger --timestats -enable >/dev/null 2>&1",
            "echo '--- how many layers ---'",
            "dumpsys SurfaceFlinger --list 2>/dev/null | wc -l",
            "echo '--- first 20 layers ---'",
            "dumpsys SurfaceFlinger --list 2>/dev/null | head -20",
            "echo '--- newest 10 layers ---'",
            "dumpsys SurfaceFlinger --list 2>/dev/null | tail -10",
            "echo '--- layers of $app ---'",
            "dumpsys SurfaceFlinger --list 2>/dev/null | grep -F '$app' | head -8",
            "echo '--- latency asked the way the app asks it ---'",
            "L=\$(dumpsys SurfaceFlinger --timestats -dump 2>/dev/null | grep -m1 '^layerName = ' | sed 's/^layerName = //'); " +
                "echo \"layer=[\$L] rows=\$(dumpsys SurfaceFlinger --latency \"\$L\" 2>/dev/null | wc -l)\"; " +
                "if [ -n \"\$L\" ]; then dumpsys SurfaceFlinger --latency \"\$L\" 2>/dev/null | tail -4; fi",
            "echo '--- latency asked with the list entry (this is what never worked) ---'",
            "D=\$(dumpsys SurfaceFlinger --list 2>/dev/null | grep -m1 -F '$app'); " +
                "echo \"descriptor rows=\$(dumpsys SurfaceFlinger --latency \"\$D\" 2>/dev/null | wc -l)\"; " +
                "if [ -n \"\$D\" ]; then dumpsys SurfaceFlinger --latency \"\$D\" 2>/dev/null | tail -2; fi",
            "echo '--- latency of the newest layer ---'",
            "N=\$(dumpsys SurfaceFlinger --timestats -dump 2>/dev/null | grep '^layerName = ' | tail -1 | sed 's/^layerName = //'); " +
                "echo \"layer=[\$N] rows=\$(dumpsys SurfaceFlinger --latency \"\$N\" 2>/dev/null | wc -l)\"",
            "echo '--- timestats: the layer blocks ---'",
            "dumpsys SurfaceFlinger --timestats -dump 2>&1 | grep -A14 -m4 'layerName = '",
            "echo '--- counters now ---'",
            "dumpsys SurfaceFlinger --timestats -dump 2>/dev/null | " +
                "grep -E 'displayRefreshRate = |renderRate = |uid = |layerName = |totalFrames = ' | head -60",
            "sleep 2",
            "echo '--- counters two seconds later ---'",
            "dumpsys SurfaceFlinger --timestats -dump 2>/dev/null | " +
                "grep -E 'displayRefreshRate = |renderRate = |uid = |layerName = |totalFrames = ' | head -60",
            "echo '--- gfxinfo of $app ---'",
            "if [ -n '$app' ]; then dumpsys gfxinfo '$app' framestats 2>&1 | head -6; fi",
            "echo '--- the window in front ---'",
            "dumpsys window 2>/dev/null | grep -m2 -E 'mCurrentFocus|mFocusedApp'",
            // Repeat the marker at the end. The on-screen block keeps only its
            // first lines, so this copy is the one that is always visible.
            "echo 'captured_from=$app at=$atMs'"
        ).joinToString("; ")
    }

    /** Puts the text on the clipboard so it can be pasted into a chat. */
    fun copy(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("FrameScope diagnostics", text))
    }
}
