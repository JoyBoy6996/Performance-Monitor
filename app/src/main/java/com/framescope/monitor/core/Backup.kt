package com.framescope.monitor.core

import android.content.Context
import com.framescope.monitor.metrics.SessionStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A single file that holds everything the app knows: the recorded sessions and every setting.
 *
 * It exists so the app never has to be uninstalled to be replaced. Export it before an update,
 * import it afterwards, and the history survives.
 */
object Backup {

    const val FILE_NAME = "framescope-backup.json"
    private const val VERSION = 1

    /** Writes the backup into the cache directory and returns the file. */
    fun export(context: Context, store: SessionStore, settings: AppSettings): File {
        val sessions = JSONArray()
        store.sessions.value.forEach { sessions.put(JSONObject(store.toJson(it))) }
        val payload = JSONObject().apply {
            put("version", VERSION)
            put("exportedAt", System.currentTimeMillis())
            put("settings", settings.exportAll())
            put("sessions", sessions)
        }
        val file = File(context.cacheDir, FILE_NAME)
        file.writeText(payload.toString())
        return file
    }

    /**
     * Restores a backup. Sessions that already exist are replaced, new ones are added.
     * Returns the number of sessions that were restored.
     */
    fun import(text: String, store: SessionStore, settings: AppSettings): Int {
        val payload = JSONObject(text)
        val sessions = payload.optJSONArray("sessions")
        var restored = 0
        if (sessions != null) {
            val records = (0 until sessions.length()).mapNotNull { index ->
                runCatching {
                    store.recordFromJson(sessions.getJSONObject(index).toString())
                }.getOrNull()
            }
            store.importRecords(records)
            restored = records.size
        }
        payload.optJSONObject("settings")?.let { settings.importAll(it) }
        return restored
    }
}
