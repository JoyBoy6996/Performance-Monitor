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
        "echo '--- display refresh period ns (6944444 = 144 Hz) ---'",
        "dumpsys SurfaceFlinger --latency 2>/dev/null | head -1",
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
        val safeDebug = debugText.replace("\"", "").replace("`", "")
        // Keep this command plain. Nested quotes and command substitution made
        // the shell answer `syntax error: unexpected ';'` on the device, which
        // left no counters to read. Every line here is a simple command.
        return listOf(
            "echo 'captured_from=$app at=$atMs'",
            if (safeDebug.isNotBlank()) {
                "echo '--- last 5 polled FPS readings (newest last) ---'; " +
                    "echo \"$safeDebug\""
            } else {
                "echo '--- last 5 polled FPS readings: none ---'"
            },
            if (layer.isBlank()) {
                "echo '--- the layer that was measured: none yet ---'"
            } else {
                // The name is the one SurfaceFlinger knows the layer by, including the
                // creation stamp. It is asked for in single quotes, and a quote inside
                // the name is removed instead of closing the quote.
                "echo '--- the layer that was measured ---'; echo 'layer=[$layer]';\n" +
                    "echo -n 'rows='; dumpsys SurfaceFlinger --latency '$layer' 2>/dev/null | wc -l;\n" +
                    "dumpsys SurfaceFlinger --latency '$layer' 2>/dev/null | tail -4"
            },
            "echo '--- display refresh period ns (6944444 = 144 Hz) ---'",
            "dumpsys SurfaceFlinger --latency 2>/dev/null | head -1",
            "echo '--- counters of $app ---'",
            "dumpsys SurfaceFlinger --timestats -dump 2>/dev/null | " +
                "grep -B 6 -A 14 -F '$app' | head -50",
            "echo '--- counter summary ---'",
            "dumpsys SurfaceFlinger --timestats -dump 2>/dev/null | " +
                "grep -E 'layerName = |totalFrames = |renderRate = |displayRefreshRate = ' | head -60",
            "echo '--- who is running this ---'",
            "id 2>/dev/null",
            "sleep 2",
            "echo '--- counter summary two seconds later ---'",
            "dumpsys SurfaceFlinger --timestats -dump 2>/dev/null | " +
                "grep -E 'layerName = |totalFrames = |renderRate = |displayRefreshRate = ' | head -60",
            "echo '--- layers of $app ---'",
            "dumpsys SurfaceFlinger --list 2>/dev/null | grep -F '$app' | head -8",
            "echo '--- the window in front ---'",
            "dumpsys window 2>/dev/null | grep -m2 -E 'mCurrentFocus|mFocusedApp'",
            "echo 'captured_from=$app at=$atMs'"
        ).joinToString("; ")
    }

    /** Puts the text on the clipboard so it can be pasted into a chat. */
    fun copy(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Qstats diagnostics", text))
    }
}
