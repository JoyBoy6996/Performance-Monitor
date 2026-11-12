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

    /** Puts the text on the clipboard so it can be pasted into a chat. */
    fun copy(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("FrameScope diagnostics", text))
    }
}
