package com.framescope.monitor.metrics

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.framescope.monitor.core.SessionRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists recorded sessions and per-interval samples using an internal SQLite database,
 * with JSON backup support for easy export and sharing.
 */
class SessionStore(context: Context) {

    private val dbHelper = SessionDatabaseHelper(context)
    private val dir = File(context.filesDir, "sessions").apply { mkdirs() }

    private val _sessions = MutableStateFlow<List<SessionRecord>>(emptyList())
    val sessions: StateFlow<List<SessionRecord>> = _sessions.asStateFlow()

    init {
        load()
    }

    @Synchronized
    private fun load() {
        // Read from SQLite database
        val dbRecords = dbHelper.getAllRecords()
        if (dbRecords.isNotEmpty()) {
            _sessions.value = dbRecords
            return
        }

        // Migration: check legacy JSON files if DB is empty
        val files = dir.listFiles { f -> f.extension == "json" } ?: emptyArray()
        val jsonRecords = files.mapNotNull { runCatching { fromJson(it.readText()) }.getOrNull() }
            .filter { it.totalFrames > 0 }
            .sortedByDescending { it.startedAt }

        if (jsonRecords.isNotEmpty()) {
            jsonRecords.forEach { dbHelper.insertRecord(it) }
            _sessions.value = jsonRecords
        }
    }

    @Synchronized
    fun add(record: SessionRecord) {
        dbHelper.insertRecord(record)
        runCatching { File(dir, "${record.id}.json").writeText(toJson(record)) }
        _sessions.value = listOf(record) + _sessions.value.filterNot { it.id == record.id }
    }

    @Synchronized
    fun addSample(sample: SessionSample) {
        dbHelper.insertSample(sample)
    }

    @Synchronized
    fun delete(id: String) {
        dbHelper.deleteRecord(id)
        runCatching { File(dir, "$id.json").delete() }
        _sessions.value = _sessions.value.filterNot { it.id == id }
    }

    @Synchronized
    fun importRecords(records: List<SessionRecord>) {
        records.forEach { record ->
            dbHelper.insertRecord(record)
            runCatching { File(dir, "${record.id}.json").writeText(toJson(record)) }
        }
        load()
    }

    @Synchronized
    fun clear() {
        dbHelper.clearAll()
        dir.listFiles { f -> f.extension == "json" }?.forEach { it.delete() }
        _sessions.value = emptyList()
    }

    fun toJson(record: SessionRecord): String = JSONObject().apply {
        put("id", record.id)
        put("appPackage", record.appPackage)
        put("appLabel", record.appLabel)
        put("startedAt", record.startedAt)
        put("durationMs", record.durationMs)
        put("avgFps", record.avgFps.toDouble())
        put("maxFps", record.maxFps.toDouble())
        put("minFps", record.minFps.toDouble())
        put("low1Percent", record.low1Percent.toDouble())
        put("low5Percent", record.low5Percent.toDouble())
        put("smoothness", record.smoothness.toDouble())
        put("totalFrames", record.totalFrames)
        put("jankFrames", record.jankFrames)
        put("avgFrameTimeMs", record.avgFrameTimeMs.toDouble())
        put("maxFrameTimeMs", record.maxFrameTimeMs.toDouble())
        put("maxTempC", record.maxTempC?.toDouble() ?: JSONObject.NULL)
        put("avgCpu", record.avgCpu?.toDouble() ?: JSONObject.NULL)
        put("fpsSeries", JSONArray(record.fpsSeries.map { it.toDouble() }))
        put("cpuSeries", JSONArray(record.cpuSeries.map { it.toDouble() }))
        put("tempSeries", JSONArray(record.tempSeries.map { it.toDouble() }))
        put("frameTimeSeries", JSONArray(record.frameTimeSeries.map { it.toDouble() }))
        put("batterySeries", JSONArray(record.batterySeries.map { it.toDouble() }))
        put("longestFrameMs", record.longestFrameMs.toDouble())
        put("dropSeconds", record.dropSeconds)
    }.toString()

    fun recordFromJson(text: String): SessionRecord = fromJson(text)

    private fun fromJson(text: String): SessionRecord {
        val o = JSONObject(text)
        return SessionRecord(
            id = o.getString("id"),
            appPackage = o.optString("appPackage"),
            appLabel = o.optString("appLabel"),
            startedAt = o.optLong("startedAt"),
            durationMs = o.optLong("durationMs"),
            avgFps = o.optDouble("avgFps", 0.0).toFloat(),
            maxFps = o.optDouble("maxFps", 0.0).toFloat(),
            minFps = o.optDouble("minFps", 0.0).toFloat(),
            low1Percent = o.optDouble("low1Percent", 0.0).toFloat(),
            low5Percent = o.optDouble("low5Percent", 0.0).toFloat(),
            smoothness = o.optDouble("smoothness", 0.0).toFloat(),
            totalFrames = o.optInt("totalFrames"),
            jankFrames = o.optInt("jankFrames"),
            avgFrameTimeMs = o.optDouble("avgFrameTimeMs", 0.0).toFloat(),
            maxFrameTimeMs = o.optDouble("maxFrameTimeMs", 0.0).toFloat(),
            maxTempC = if (o.isNull("maxTempC")) null else o.optDouble("maxTempC").toFloat(),
            avgCpu = if (o.isNull("avgCpu")) null else o.optDouble("avgCpu").toFloat(),
            fpsSeries = o.optJSONArray("fpsSeries").toFloatList(),
            cpuSeries = o.optJSONArray("cpuSeries").toFloatList(),
            tempSeries = o.optJSONArray("tempSeries").toFloatList(),
            frameTimeSeries = o.optJSONArray("frameTimeSeries").toFloatList(),
            batterySeries = o.optJSONArray("batterySeries").toFloatList(),
            longestFrameMs = o.optDouble("longestFrameMs", 0.0).toFloat(),
            dropSeconds = o.optInt("dropSeconds")
        )
    }

    fun toCsv(record: SessionRecord): String {
        val sb = StringBuilder()
        sb.append("second,fps,frame_time_ms,cpu_percent,temp_c,battery_percent\n")
        val size = maxOf(
            record.fpsSeries.size,
            record.cpuSeries.size,
            record.tempSeries.size,
            record.frameTimeSeries.size,
            record.batterySeries.size
        )
        for (i in 0 until size) {
            sb.append(i).append(',')
            sb.append(record.fpsSeries.getOrElse(i) { "" }).append(',')
            sb.append(record.frameTimeSeries.getOrElse(i) { "" }).append(',')
            sb.append(record.cpuSeries.getOrElse(i) { "" }).append(',')
            sb.append(record.tempSeries.getOrElse(i) { "" }).append(',')
            sb.append(record.batterySeries.getOrElse(i) { "" })
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun JSONArray?.toFloatList(): List<Float> {
        if (this == null) return emptyList()
        val out = ArrayList<Float>(length())
        for (i in 0 until length()) {
            out.add(optDouble(i, 0.0).toFloat())
        }
        return out
    }

    private class SessionDatabaseHelper(context: Context) :
        SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE_RECORDS (
                    id TEXT PRIMARY KEY,
                    app_package TEXT,
                    app_label TEXT,
                    started_at INTEGER,
                    duration_ms INTEGER,
                    avg_fps REAL,
                    max_fps REAL,
                    min_fps REAL,
                    low1_percent REAL,
                    low5_percent REAL,
                    smoothness REAL,
                    total_frames INTEGER,
                    jank_frames INTEGER,
                    avg_frame_time_ms REAL,
                    max_frame_time_ms REAL,
                    max_temp_c REAL,
                    avg_cpu REAL,
                    longest_frame_ms REAL,
                    drop_seconds INTEGER,
                    fps_series TEXT,
                    cpu_series TEXT,
                    temp_series TEXT,
                    frame_time_series TEXT,
                    battery_series TEXT
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE $TABLE_SAMPLES (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id TEXT,
                    timestamp INTEGER,
                    fps REAL,
                    frame_time_ms REAL,
                    cpu_percent REAL,
                    gpu_freq_mhz INTEGER,
                    thermal_max_c REAL,
                    battery_level INTEGER,
                    ram_percent INTEGER
                )
                """.trimIndent()
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_SAMPLES")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_RECORDS")
            onCreate(db)
        }

        fun insertRecord(record: SessionRecord) {
            val cv = ContentValues().apply {
                put("id", record.id)
                put("app_package", record.appPackage)
                put("app_label", record.appLabel)
                put("started_at", record.startedAt)
                put("duration_ms", record.durationMs)
                put("avg_fps", record.avgFps)
                put("max_fps", record.maxFps)
                put("min_fps", record.minFps)
                put("low1_percent", record.low1Percent)
                put("low5_percent", record.low5Percent)
                put("smoothness", record.smoothness)
                put("total_frames", record.totalFrames)
                put("jank_frames", record.jankFrames)
                put("avg_frame_time_ms", record.avgFrameTimeMs)
                put("max_frame_time_ms", record.maxFrameTimeMs)
                put("max_temp_c", record.maxTempC)
                put("avg_cpu", record.avgCpu)
                put("longest_frame_ms", record.longestFrameMs)
                put("drop_seconds", record.dropSeconds)
                put("fps_series", JSONArray(record.fpsSeries.map { it.toDouble() }).toString())
                put("cpu_series", JSONArray(record.cpuSeries.map { it.toDouble() }).toString())
                put("temp_series", JSONArray(record.tempSeries.map { it.toDouble() }).toString())
                put("frame_time_series", JSONArray(record.frameTimeSeries.map { it.toDouble() }).toString())
                put("battery_series", JSONArray(record.batterySeries.map { it.toDouble() }).toString())
            }
            writableDatabase.insertWithOnConflict(
                TABLE_RECORDS,
                null,
                cv,
                SQLiteDatabase.CONFLICT_REPLACE
            )
        }

        fun insertSample(sample: SessionSample) {
            val cv = ContentValues().apply {
                put("session_id", sample.sessionId)
                put("timestamp", sample.timestamp)
                put("fps", sample.fps)
                put("frame_time_ms", sample.frameTimeMs)
                put("cpu_percent", sample.cpuPercent)
                put("gpu_freq_mhz", sample.gpuFreqMHz)
                put("thermal_max_c", sample.thermalMaxC)
                put("battery_level", sample.batteryLevel)
                put("ram_percent", sample.ramPercent)
            }
            writableDatabase.insert(TABLE_SAMPLES, null, cv)
        }

        fun getAllRecords(): List<SessionRecord> {
            val list = ArrayList<SessionRecord>()
            val cursor = readableDatabase.query(
                TABLE_RECORDS,
                null,
                null,
                null,
                null,
                null,
                "started_at DESC"
            )
            cursor.use { c ->
                while (c.moveToNext()) {
                    val maxTempIdx = c.getColumnIndex("max_temp_c")
                    val avgCpuIdx = c.getColumnIndex("avg_cpu")
                    list.add(
                        SessionRecord(
                            id = c.getString(c.getColumnIndexOrThrow("id")),
                            appPackage = c.getString(c.getColumnIndexOrThrow("app_package")),
                            appLabel = c.getString(c.getColumnIndexOrThrow("app_label")),
                            startedAt = c.getLong(c.getColumnIndexOrThrow("started_at")),
                            durationMs = c.getLong(c.getColumnIndexOrThrow("duration_ms")),
                            avgFps = c.getFloat(c.getColumnIndexOrThrow("avg_fps")),
                            maxFps = c.getFloat(c.getColumnIndexOrThrow("max_fps")),
                            minFps = c.getFloat(c.getColumnIndexOrThrow("min_fps")),
                            low1Percent = c.getFloat(c.getColumnIndexOrThrow("low1_percent")),
                            low5Percent = c.getFloat(c.getColumnIndexOrThrow("low5_percent")),
                            smoothness = c.getFloat(c.getColumnIndexOrThrow("smoothness")),
                            totalFrames = c.getInt(c.getColumnIndexOrThrow("total_frames")),
                            jankFrames = c.getInt(c.getColumnIndexOrThrow("jank_frames")),
                            avgFrameTimeMs = c.getFloat(c.getColumnIndexOrThrow("avg_frame_time_ms")),
                            maxFrameTimeMs = c.getFloat(c.getColumnIndexOrThrow("max_frame_time_ms")),
                            maxTempC = if (maxTempIdx >= 0 && !c.isNull(maxTempIdx)) c.getFloat(maxTempIdx) else null,
                            avgCpu = if (avgCpuIdx >= 0 && !c.isNull(avgCpuIdx)) c.getFloat(avgCpuIdx) else null,
                            fpsSeries = jsonToFloatList(c.getString(c.getColumnIndexOrThrow("fps_series"))),
                            cpuSeries = jsonToFloatList(c.getString(c.getColumnIndexOrThrow("cpu_series"))),
                            tempSeries = jsonToFloatList(c.getString(c.getColumnIndexOrThrow("temp_series"))),
                            frameTimeSeries = jsonToFloatList(c.getString(c.getColumnIndexOrThrow("frame_time_series"))),
                            batterySeries = jsonToFloatList(c.getString(c.getColumnIndexOrThrow("battery_series"))),
                            longestFrameMs = c.getFloat(c.getColumnIndexOrThrow("longest_frame_ms")),
                            dropSeconds = c.getInt(c.getColumnIndexOrThrow("drop_seconds"))
                        )
                    )
                }
            }
            return list
        }

        fun deleteRecord(id: String) {
            writableDatabase.delete(TABLE_RECORDS, "id = ?", arrayOf(id))
            writableDatabase.delete(TABLE_SAMPLES, "session_id = ?", arrayOf(id))
        }

        fun clearAll() {
            writableDatabase.delete(TABLE_RECORDS, null, null)
            writableDatabase.delete(TABLE_SAMPLES, null, null)
        }

        private fun jsonToFloatList(json: String?): List<Float> {
            if (json.isNullOrBlank()) return emptyList()
            val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
            val list = ArrayList<Float>(arr.length())
            for (i in 0 until arr.length()) {
                list.add(arr.optDouble(i, 0.0).toFloat())
            }
            return list
        }

        companion object {
            private const val DB_NAME = "framescope_sessions.db"
            private const val DB_VERSION = 1
            private const val TABLE_RECORDS = "session_records"
            private const val TABLE_SAMPLES = "session_samples"
        }
    }
}
