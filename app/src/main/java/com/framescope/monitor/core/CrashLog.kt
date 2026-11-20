package com.framescope.monitor.core

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The last crash, kept on disk.
 *
 * A crash on the phone is invisible from here: the app simply does not open.
 * Writing the stack trace to a file lets the app show it on the next launch
 * and include it in the diagnostics.
 */
object CrashLog {

    private const val FILE = "last-crash.txt"

    private fun file(context: Context): File = File(context.filesDir, FILE)

    /**
     * A second copy on shared storage. The internal file needs `run-as`, which
     * is not always available; this one can be read from a phone shell at
     * /sdcard/Android/data/com.framescope.monitor/files/last-crash.txt
     */
    private fun externalFile(context: Context): File? =
        context.getExternalFilesDir(null)?.let { File(it, FILE) }

    /** Keeps the next crash instead of losing it with the process. */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun write(context: Context, error: Throwable) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val trace = error.stackTraceToString()
        val text = "$stamp\n${error.javaClass.name}: ${error.message}\n\n$trace"
        runCatching { file(context).writeText(text) }
        runCatching { externalFile(context)?.writeText(text) }
    }

    /** The text of the last crash, or null when there was none. */
    fun last(context: Context): String? {
        val candidates = listOfNotNull(file(context), externalFile(context))
        for (f in candidates) {
            runCatching {
                if (f.exists()) {
                    val text = f.readText().trim()
                    if (text.isNotEmpty()) return text
                }
            }
        }
        return null
    }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
        runCatching { externalFile(context)?.delete() }
    }
}
