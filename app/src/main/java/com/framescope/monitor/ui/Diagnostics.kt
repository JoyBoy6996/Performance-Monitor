package com.framescope.monitor.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

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
    fun probeCommand(packageName: String?): String {
        val pkg = (packageName ?: "").replace("'", "").replace("\"", "")
        val app = if (pkg.isBlank()) "" else pkg
        return listOf(
            "echo '--- who is running this ---'",
            "id 2>/dev/null",
            "echo '--- how many layers ---'",
            "dumpsys SurfaceFlinger --list 2>/dev/null | wc -l",
            "echo '--- first 20 layers ---'",
            "dumpsys SurfaceFlinger --list 2>/dev/null | head -20",
            "echo '--- newest 10 layers ---'",
            "dumpsys SurfaceFlinger --list 2>/dev/null | tail -10",
            "echo '--- layers of $app ---'",
            "dumpsys SurfaceFlinger --list 2>/dev/null | grep -F '$app' | head -8",
            "echo '--- latency of the first of them ---'",
            "L=\$(dumpsys SurfaceFlinger --list 2>/dev/null | grep -m1 -F '$app'); " +
                "echo \"layer=\$L rows=\$(dumpsys SurfaceFlinger --latency \"\$L\" 2>/dev/null | wc -l)\"; " +
                "if [ -n \"\$L\" ]; then dumpsys SurfaceFlinger --latency \"\$L\" 2>&1 | tail -6; fi",
            "echo '--- latency of the newest layer ---'",
            "N=\$(dumpsys SurfaceFlinger --list 2>/dev/null | tail -1); " +
                "echo \"layer=\$N rows=\$(dumpsys SurfaceFlinger --latency \"\$N\" 2>/dev/null | wc -l)\"; " +
                "if [ -n \"\$N\" ]; then dumpsys SurfaceFlinger --latency \"\$N\" 2>&1 | tail -6; fi",
            "echo '--- timestats: the layer blocks ---'",
            "dumpsys SurfaceFlinger --timestats -enable >/dev/null 2>&1; " +
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
            "dumpsys window 2>/dev/null | grep -m2 -E 'mCurrentFocus|mFocusedApp'"
        ).joinToString("; ")
    }

    /** Puts the text on the clipboard so it can be pasted into a chat. */
    fun copy(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("FrameScope diagnostics", text))
    }
}
