package com.framescope.monitor.metrics

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.framescope.monitor.core.CapChange
import com.framescope.monitor.core.SessionDeviceInfo
import com.framescope.monitor.core.SessionRecord
import com.framescope.monitor.core.SessionSegment
import com.framescope.monitor.core.finiteOrNull
import com.framescope.monitor.core.finiteOrZero
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Never write NaN or infinity into an export; it makes the backup invalid. */
private fun List<Float>.finiteDoubles(): List<Double> = mapNotNull { it.finiteOrNull()?.toDouble() }

/** A missing per-second reading stays null in a backup, never a made-up zero. */
private fun List<List<Float?>>.toNullableJsonArrays(): JSONArray = JSONArray().apply {
    forEach { row ->
        put(JSONArray().apply {
            row.forEach { value ->
                put(value?.finiteOrNull()?.toDouble() ?: JSONObject.NULL)
            }
        })
    }
}

/** Integer clocks keep a null per core, never a zero that looks measured. */
private fun List<List<Int?>>.toNullableIntJsonArrays(): JSONArray = JSONArray().apply {
    forEach { row ->
        put(JSONArray().apply {
            row.forEach { value ->
                put(value?.takeIf { it > 0 } ?: JSONObject.NULL)
            }
        })
    }
}

private fun JSONArray?.toNullableIntLists(): List<List<Int?>> {
    if (this == null) return emptyList()
    val out = ArrayList<List<Int?>>(length())
    for (i in 0 until length()) {
        val row = optJSONArray(i) ?: continue
        val values = ArrayList<Int?>(row.length())
        for (j in 0 until row.length()) {
            values.add(if (row.isNull(j)) null else row.optInt(j, 0).takeIf { it > 0 })
        }
        out.add(values)
    }
    return out
}

private fun JSONArray?.toNullableFloatLists(): List<List<Float?>> {
    if (this == null) return emptyList()
    val out = ArrayList<List<Float?>>(length())
    for (i in 0 until length()) {
        val row = optJSONArray(i) ?: continue
        val values = ArrayList<Float?>(row.length())
        for (j in 0 until row.length()) {
            values.add(
                if (row.isNull(j)) null else row.optDouble(j, 0.0).toFloat().finiteOrNull()
            )
        }
        out.add(values)
    }
    return out
}

/** Reads the device facts stored with a run; a missing block is simply unknown. */
private fun JSONObject?.toDeviceInfo(): SessionDeviceInfo {
    if (this == null) return SessionDeviceInfo()
    return SessionDeviceInfo(
        model = optString("model").takeIf { it.isNotBlank() },
        platform = optString("platform").takeIf { it.isNotBlank() },
        resolution = optString("resolution").takeIf { it.isNotBlank() },
        osVersion = optString("osVersion").takeIf { it.isNotBlank() },
        profile = optString("profile").takeIf { it.isNotBlank() }
    )
}

/** Reads cap changes from JSON, ignoring anything that does not parse. */
private fun JSONArray?.toCapChanges(): List<CapChange> {
    if (this == null) return emptyList()
    val out = ArrayList<CapChange>()
    for (i in 0 until length()) {
        val item = optJSONObject(i) ?: continue
        val to = item.optInt("toFps")
        if (to <= 0) continue
        out.add(
            CapChange(
                second = item.optInt("second").coerceAtLeast(0),
                fromFps = if (item.isNull("fromFps")) null else item.optInt("fromFps").takeIf { it > 0 },
                toFps = to,
                thermalStatus = if (item.isNull("thermalStatus")) null else item.optInt("thermalStatus"),
                temperatureC = if (item.isNull("temperatureC")) null else {
                    item.optDouble("temperatureC").takeIf { it.isFinite() }?.toFloat()
                },
                cause = item.optString("cause").takeIf { it == "heat" } ?: "measured"
            )
        )
    }
    return out
}

/**
 * Persists recorded sessions and per-interval samples using an internal SQLite database,
 * with JSON backup support for easy export and sharing.
 */
class SessionStore(context: Context) {

    private val dbHelper = SessionDatabaseHelper(context)
    private val dir = File(context.filesDir, "sessions").apply { mkdirs() }
    private val ioScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    private val _sessions = MutableStateFlow<List<SessionRecord>>(emptyList())
    val sessions: StateFlow<List<SessionRecord>> = _sessions.asStateFlow()

    init {
        load()
    }

    @Synchronized
    private fun load() {
        // Read from SQLite database with safety
        val dbRecords = runCatching { dbHelper.getAllRecords() }.getOrDefault(emptyList())
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
            _sessions.value = jsonRecords
            ioScope.launch {
                jsonRecords.forEach { runCatching { dbHelper.insertRecord(it) } }
            }
        }
    }

    @Synchronized
    fun add(record: SessionRecord) {
        _sessions.value = (listOf(record) + _sessions.value.filterNot { it.id == record.id }).take(MAX_RECORDS)
        ioScope.launch {
            runCatching { dbHelper.insertRecord(record) }
            runCatching { File(dir, "${record.id}.json").writeText(toJson(record)) }
            runCatching { pruneRecords() }
        }
    }

    @Synchronized
    fun addSamples(samples: List<SessionSample>) {
        if (samples.isEmpty()) return
        ioScope.launch {
            runCatching { dbHelper.insertSamples(samples) }
        }
    }

    @Synchronized
    fun delete(id: String) {
        _sessions.value = _sessions.value.filterNot { it.id == id }
        ioScope.launch {
            runCatching { dbHelper.deleteRecord(id) }
            runCatching { File(dir, "$id.json").delete() }
        }
    }

    /** Saves a user's note without touching any measured value. */
    @Synchronized
    fun updateNote(id: String, note: String) {
        val current = _sessions.value.firstOrNull { it.id == id } ?: return
        val updated = current.copy(note = note.take(2_000))
        _sessions.value = _sessions.value.map { if (it.id == id) updated else it }
        ioScope.launch {
            runCatching { dbHelper.updateNote(id, updated.note) }
            runCatching { File(dir, "$id.json").writeText(toJson(updated)) }
        }
    }

    @Synchronized
    fun importRecords(records: List<SessionRecord>) {
        _sessions.value = (records + _sessions.value).distinctBy { it.id }.take(MAX_RECORDS)
        ioScope.launch {
            records.forEach { record ->
                runCatching { dbHelper.insertRecord(record) }
                runCatching { File(dir, "${record.id}.json").writeText(toJson(record)) }
            }
            runCatching { pruneRecords() }
        }
    }

    @Synchronized
    fun clear() {
        _sessions.value = emptyList()
        ioScope.launch {
            runCatching { dbHelper.clearAll() }
            runCatching { dir.listFiles { f -> f.extension == "json" }?.forEach { it.delete() } }
        }
    }

    private fun pruneRecords() {
        val current = _sessions.value
        if (current.size > MAX_RECORDS) {
            val toRemove = current.drop(MAX_RECORDS)
            toRemove.forEach {
                runCatching { dbHelper.deleteRecord(it.id) }
                runCatching { File(dir, "${it.id}.json").delete() }
            }
        }
    }

    fun toJson(record: SessionRecord): String = JSONObject().apply {
        put("id", record.id)
        put("appPackage", record.appPackage)
        put("appLabel", record.appLabel)
        put("startedAt", record.startedAt)
        put("durationMs", record.durationMs)
        put("avgFps", record.avgFps.finiteOrZero().toDouble())
        put("maxFps", record.maxFps.finiteOrZero().toDouble())
        put("minFps", record.minFps.finiteOrZero().toDouble())
        put("low1Percent", record.low1Percent.finiteOrZero().toDouble())
        put("low5Percent", record.low5Percent.finiteOrZero().toDouble())
        put("smoothness", record.smoothness.finiteOrZero().toDouble())
        put("totalFrames", record.totalFrames)
        put("jankFrames", record.jankFrames)
        put("avgFrameTimeMs", record.avgFrameTimeMs.finiteOrZero().toDouble())
        put("maxFrameTimeMs", record.maxFrameTimeMs.finiteOrZero().toDouble())
        put("maxTempC", record.maxTempC.finiteOrNull()?.toDouble() ?: JSONObject.NULL)
        put("avgCpu", record.avgCpu.finiteOrNull()?.toDouble() ?: JSONObject.NULL)
        put("fpsSeries", JSONArray(record.fpsSeries.finiteDoubles()))
        put("seriesTimesMs", JSONArray(record.seriesTimesMs.map { it }))
        put("cpuSeries", JSONArray(record.cpuSeries.finiteDoubles()))
        put("tempSeries", JSONArray(record.tempSeries.finiteDoubles()))
        put("frameTimeSeries", JSONArray(record.frameTimeSeries.finiteDoubles()))
        put("batterySeries", JSONArray(record.batterySeries.finiteDoubles()))
        put("longestFrameMs", record.longestFrameMs.finiteOrZero().toDouble())
        put("dropSeconds", record.dropSeconds)
        put("gapSeconds", record.gapSeconds)
        put("segments", JSONArray(record.segments.map { segment ->
            JSONObject().apply {
                put("appPackage", segment.appPackage)
                put("source", segment.source)
                put("startOffsetMs", segment.startOffsetMs)
                put("endOffsetMs", segment.endOffsetMs)
                put("frames", segment.frames)
                put("averageFps", segment.averageFps.toDouble())
            }
        }))
        put("peakThermalStatus", record.peakThermalStatus ?: JSONObject.NULL)
        put("throttledSeconds", record.throttledSeconds)
        put("thermalStatusSeries", JSONArray(record.thermalStatusSeries.finiteDoubles()))
        put("jitterMs", record.jitterMs.finiteOrNull()?.toDouble() ?: JSONObject.NULL)
        put("fpsVariance", record.fpsVariance.finiteOrNull()?.toDouble() ?: JSONObject.NULL)
        put("avgPowerW", record.avgPowerW.finiteOrNull()?.toDouble() ?: JSONObject.NULL)
        put("capSeries", JSONArray(record.capSeries.finiteDoubles()))
        put("capChanges", JSONArray(record.capChanges.map { change ->
            JSONObject().apply {
                put("second", change.second)
                put("fromFps", change.fromFps ?: JSONObject.NULL)
                put("toFps", change.toFps)
                put("thermalStatus", change.thermalStatus ?: JSONObject.NULL)
                put("temperatureC", change.temperatureC?.toDouble() ?: JSONObject.NULL)
                put("cause", change.cause)
            }
        }))
        put("thermalCapSeconds", record.thermalCapSeconds)
        put("sceneSmoothnessPercent", record.sceneSmoothnessPercent.finiteOrNull()?.toDouble() ?: JSONObject.NULL)
        put("powerSeries", JSONArray(record.powerSeries.finiteDoubles()))
        put("cpuFreqSeries", JSONArray(record.cpuFreqSeries.finiteDoubles()))
        put("gpuFreqSeries", JSONArray(record.gpuFreqSeries.finiteDoubles()))
        put("cpuTempSeries", JSONArray(record.cpuTempSeries.finiteDoubles()))
        put("socTempSeries", JSONArray(record.socTempSeries.finiteDoubles()))
        put("batteryTempSeries", JSONArray(record.batteryTempSeries.finiteDoubles()))
        put("gpuPercentSeries", JSONArray(record.gpuPercentSeries.finiteDoubles()))
        put("cpuGroupNames", JSONArray(record.cpuGroupNames))
        put("cpuGroupSeries", record.cpuGroupSeries.toNullableJsonArrays())
        put("cpuGroupFreqSeries", record.cpuGroupFreqSeries.toNullableJsonArrays())
        put("perCoreLoadSeries", record.perCoreLoadSeries.toNullableJsonArrays())
        put("perCoreFreqSeries", record.perCoreFreqSeries.toNullableIntJsonArrays())
        put("ddrFreqSeries", JSONArray(record.ddrFreqSeries))
        put("threadNames", JSONArray(record.threadNames))
        put("threadAvgLoads", JSONArray(record.threadAvgLoads.finiteDoubles()))
        put("threadMaxLoads", JSONArray(record.threadMaxLoads.finiteDoubles()))
        put("avgCurrentMa", record.avgCurrentMa.finiteOrNull()?.toDouble() ?: JSONObject.NULL)
        put("voltageMv", record.voltageMv ?: JSONObject.NULL)
        put("batteryCycles", record.batteryCycles ?: JSONObject.NULL)
        put("shellLogs", record.shellLogs)
        put("note", record.note)
        put("displayHz", record.displayHz.finiteOrNull()?.toDouble() ?: JSONObject.NULL)
        put("device", JSONObject().apply {
            put("model", record.device.model ?: JSONObject.NULL)
            put("platform", record.device.platform ?: JSONObject.NULL)
            put("resolution", record.device.resolution ?: JSONObject.NULL)
            put("osVersion", record.device.osVersion ?: JSONObject.NULL)
            put("profile", record.device.profile ?: JSONObject.NULL)
        })
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
            avgFps = o.optDouble("avgFps", 0.0).toFloat().finiteOrZero(),
            maxFps = o.optDouble("maxFps", 0.0).toFloat().finiteOrZero(),
            minFps = o.optDouble("minFps", 0.0).toFloat().finiteOrZero(),
            low1Percent = o.optDouble("low1Percent", 0.0).toFloat().finiteOrZero(),
            low5Percent = o.optDouble("low5Percent", 0.0).toFloat().finiteOrZero(),
            smoothness = o.optDouble("smoothness", 0.0).toFloat().finiteOrZero(),
            totalFrames = o.optInt("totalFrames"),
            jankFrames = o.optInt("jankFrames"),
            avgFrameTimeMs = o.optDouble("avgFrameTimeMs", 0.0).toFloat().finiteOrZero(),
            maxFrameTimeMs = o.optDouble("maxFrameTimeMs", 0.0).toFloat().finiteOrZero(),
            maxTempC = if (o.isNull("maxTempC")) null else o.optDouble("maxTempC").toFloat().finiteOrNull(),
            avgCpu = if (o.isNull("avgCpu")) null else o.optDouble("avgCpu").toFloat().finiteOrNull(),
            fpsSeries = o.optJSONArray("fpsSeries").toFloatList(),
            seriesTimesMs = o.optJSONArray("seriesTimesMs").toLongList(),
            cpuSeries = o.optJSONArray("cpuSeries").toFloatList(),
            tempSeries = o.optJSONArray("tempSeries").toFloatList(),
            frameTimeSeries = o.optJSONArray("frameTimeSeries").toFloatList(),
            batterySeries = o.optJSONArray("batterySeries").toFloatList(),
            longestFrameMs = o.optDouble("longestFrameMs", 0.0).toFloat().finiteOrZero(),
            dropSeconds = o.optInt("dropSeconds"),
            gapSeconds = o.optInt("gapSeconds"),
            segments = o.optJSONArray("segments").toSegments(),
            peakThermalStatus = if (o.isNull("peakThermalStatus")) null else o.optInt("peakThermalStatus"),
            throttledSeconds = o.optInt("throttledSeconds"),
            thermalStatusSeries = o.optJSONArray("thermalStatusSeries").toFloatList(),
            jitterMs = if (o.isNull("jitterMs")) null else o.optDouble("jitterMs").toFloat().finiteOrNull(),
            fpsVariance = if (o.isNull("fpsVariance")) null else o.optDouble("fpsVariance").toFloat().finiteOrNull(),
            avgPowerW = if (o.isNull("avgPowerW")) null else o.optDouble("avgPowerW").toFloat().finiteOrNull(),
            capSeries = o.optJSONArray("capSeries").toFloatList(),
            capChanges = o.optJSONArray("capChanges").toCapChanges(),
            thermalCapSeconds = o.optInt("thermalCapSeconds"),
            sceneSmoothnessPercent = if (o.isNull("sceneSmoothnessPercent")) null else {
                o.optDouble("sceneSmoothnessPercent").toFloat().finiteOrNull()
            },
            powerSeries = o.optJSONArray("powerSeries").toFloatList(),
            cpuFreqSeries = o.optJSONArray("cpuFreqSeries").toFloatList(),
            gpuFreqSeries = o.optJSONArray("gpuFreqSeries").toFloatList(),
            cpuTempSeries = o.optJSONArray("cpuTempSeries").toFloatList(),
            socTempSeries = o.optJSONArray("socTempSeries").toFloatList(),
            batteryTempSeries = o.optJSONArray("batteryTempSeries").toFloatList(),
            gpuPercentSeries = o.optJSONArray("gpuPercentSeries").toFloatList(),
            cpuGroupNames = o.optJSONArray("cpuGroupNames")?.let { names ->
                (0 until names.length()).mapNotNull { i ->
                    names.optString(i).takeIf { it.isNotBlank() }
                }
            }.orEmpty(),
            cpuGroupSeries = o.optJSONArray("cpuGroupSeries").toNullableFloatLists(),
            cpuGroupFreqSeries = o.optJSONArray("cpuGroupFreqSeries").toNullableFloatLists(),
            perCoreLoadSeries = o.optJSONArray("perCoreLoadSeries").toNullableFloatLists(),
            perCoreFreqSeries = o.optJSONArray("perCoreFreqSeries").toNullableIntLists(),
            ddrFreqSeries = o.optJSONArray("ddrFreqSeries")?.let { arr ->
                (0 until arr.length()).map { arr.optInt(it, 0) }
            }.orEmpty(),
            threadNames = o.optJSONArray("threadNames")?.let { arr ->
                (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
            }.orEmpty(),
            threadAvgLoads = o.optJSONArray("threadAvgLoads").toFloatList(),
            threadMaxLoads = o.optJSONArray("threadMaxLoads").toFloatList(),
            avgCurrentMa = if (o.isNull("avgCurrentMa")) null else {
                o.optDouble("avgCurrentMa").toFloat().finiteOrNull()
            },
            voltageMv = if (o.isNull("voltageMv")) null else o.optInt("voltageMv").takeIf { it > 0 },
            batteryCycles = if (o.isNull("batteryCycles")) null else {
                o.optInt("batteryCycles").takeIf { it >= 0 }
            },
            shellLogs = o.optString("shellLogs"),
            note = o.optString("note"),
            displayHz = if (o.isNull("displayHz")) null else {
                o.optDouble("displayHz").toFloat().finiteOrNull()
            },
            device = o.optJSONObject("device").toDeviceInfo()
        )
    }

    private fun JSONArray?.toFloatList(): List<Float> {
        if (this == null) return emptyList()
        val out = ArrayList<Float>(length())
        for (i in 0 until length()) {
            val value = optDouble(i, 0.0).toFloat()
            if (value.finiteOrNull() != null) out.add(value)
        }
        return out
    }

    /** Reads the moments of the recorded seconds. */
    private fun JSONArray?.toLongList(): List<Long> {
        if (this == null) return emptyList()
        val out = ArrayList<Long>(length())
        for (i in 0 until length()) {
            val value = optLong(i, 0L)
            if (value >= 0L) out.add(value)
        }
        return out
    }

    /** Reads the segments of a run, ignoring anything that does not parse. */
    private fun JSONArray?.toSegments(): List<SessionSegment> {
        if (this == null) return emptyList()
        val out = ArrayList<SessionSegment>()
        for (i in 0 until length()) {
            val item = optJSONObject(i) ?: continue
            out.add(
                SessionSegment(
                    appPackage = item.optString("appPackage"),
                    source = item.optString("source"),
                    startOffsetMs = item.optLong("startOffsetMs"),
                    endOffsetMs = item.optLong("endOffsetMs"),
                    frames = item.optInt("frames"),
                    averageFps = item.optDouble("averageFps", 0.0).toFloat()
                )
            )
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
                    series_times_ms TEXT,
                    cpu_series TEXT,
                    temp_series TEXT,
                    frame_time_series TEXT,
                    battery_series TEXT,
                    peak_thermal_status INTEGER,
                    throttled_seconds INTEGER DEFAULT 0,
                    thermal_status_series TEXT,
                    jitter_ms REAL,
                    fps_variance REAL,
                    avg_power_w REAL,
                    cap_series TEXT,
                    cap_changes TEXT,
                    thermal_cap_seconds INTEGER DEFAULT 0,
                    scene_smoothness_percent REAL,
                    device_model TEXT,
                    device_platform TEXT,
                    device_resolution TEXT,
                    device_os TEXT,
                    device_profile TEXT,
                    power_series TEXT,
                    cpu_freq_series TEXT,
                    gpu_freq_series TEXT,
                    cpu_temp_series TEXT,
                    soc_temp_series TEXT,
                    battery_temp_series TEXT,
                    gpu_percent_series TEXT,
                    cpu_group_names TEXT,
                    cpu_group_series TEXT,
                    cpu_group_freq_series TEXT,
                    per_core_load_series TEXT,
                    per_core_freq_series TEXT,
                    ddr_freq_series TEXT,
                    thread_names TEXT,
                    thread_avg_loads TEXT,
                    thread_max_loads TEXT,
                    avg_current_ma REAL,
                    voltage_mv INTEGER,
                    battery_cycles INTEGER,
                    shell_logs TEXT,
                    note TEXT,
                    display_hz REAL
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
                    ram_percent INTEGER,
                    thermal_status INTEGER,
                    power_watts REAL
                )
                """.trimIndent()
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Keep existing data on upgrade; do not drop tables.
            if (oldVersion < 12) {
                addColumnIfMissing(db, TABLE_RECORDS, "series_times_ms", "TEXT")
            }
            if (oldVersion < 2) {
                addColumnIfMissing(db, TABLE_RECORDS, "peak_thermal_status", "INTEGER")
                addColumnIfMissing(db, TABLE_RECORDS, "throttled_seconds", "INTEGER DEFAULT 0")
                addColumnIfMissing(db, TABLE_RECORDS, "thermal_status_series", "TEXT")
                addColumnIfMissing(db, TABLE_SAMPLES, "thermal_status", "INTEGER")
            }
            if (oldVersion < 3) {
                addColumnIfMissing(db, TABLE_RECORDS, "jitter_ms", "REAL")
                addColumnIfMissing(db, TABLE_RECORDS, "fps_variance", "REAL")
                addColumnIfMissing(db, TABLE_RECORDS, "avg_power_w", "REAL")
                addColumnIfMissing(db, TABLE_SAMPLES, "power_watts", "REAL")
            }
            if (oldVersion < 4) {
                addColumnIfMissing(db, TABLE_RECORDS, "cap_series", "TEXT")
                addColumnIfMissing(db, TABLE_RECORDS, "cap_changes", "TEXT")
                addColumnIfMissing(db, TABLE_RECORDS, "thermal_cap_seconds", "INTEGER DEFAULT 0")
            }
            if (oldVersion < 5) {
                addColumnIfMissing(db, TABLE_RECORDS, "scene_smoothness_percent", "REAL")
                addColumnIfMissing(db, TABLE_RECORDS, "device_model", "TEXT")
                addColumnIfMissing(db, TABLE_RECORDS, "device_platform", "TEXT")
                addColumnIfMissing(db, TABLE_RECORDS, "device_resolution", "TEXT")
                addColumnIfMissing(db, TABLE_RECORDS, "device_os", "TEXT")
            }
            if (oldVersion < 6) {
                addColumnIfMissing(db, TABLE_RECORDS, "power_series", "TEXT")
                addColumnIfMissing(db, TABLE_RECORDS, "cpu_freq_series", "TEXT")
                addColumnIfMissing(db, TABLE_RECORDS, "gpu_freq_series", "TEXT")
                addColumnIfMissing(db, TABLE_RECORDS, "cpu_temp_series", "TEXT")
                addColumnIfMissing(db, TABLE_RECORDS, "gpu_percent_series", "TEXT")
                addColumnIfMissing(db, TABLE_RECORDS, "cpu_group_names", "TEXT")
                addColumnIfMissing(db, TABLE_RECORDS, "cpu_group_series", "TEXT")
                addColumnIfMissing(db, TABLE_RECORDS, "cpu_group_freq_series", "TEXT")
                addColumnIfMissing(db, TABLE_RECORDS, "shell_logs", "TEXT")
                addColumnIfMissing(db, TABLE_RECORDS, "note", "TEXT")
            }
            if (oldVersion < 7) {
                addColumnIfMissing(db, TABLE_RECORDS, "soc_temp_series", "TEXT")
            }
            if (oldVersion < 8) {
                addColumnIfMissing(db, TABLE_RECORDS, "per_core_load_series", "TEXT")
                addColumnIfMissing(db, TABLE_RECORDS, "per_core_freq_series", "TEXT")
                addColumnIfMissing(db, TABLE_RECORDS, "ddr_freq_series", "TEXT")
                addColumnIfMissing(db, TABLE_RECORDS, "thread_names", "TEXT")
                addColumnIfMissing(db, TABLE_RECORDS, "thread_avg_loads", "TEXT")
                addColumnIfMissing(db, TABLE_RECORDS, "thread_max_loads", "TEXT")
                addColumnIfMissing(db, TABLE_RECORDS, "avg_current_ma", "REAL")
                addColumnIfMissing(db, TABLE_RECORDS, "voltage_mv", "INTEGER")
                addColumnIfMissing(db, TABLE_RECORDS, "battery_cycles", "INTEGER")
            }
            if (oldVersion < 9) {
                addColumnIfMissing(db, TABLE_RECORDS, "display_hz", "REAL")
            }
            if (oldVersion < 10) {
                addColumnIfMissing(db, TABLE_RECORDS, "device_profile", "TEXT")
            }
            if (oldVersion < 11) {
                addColumnIfMissing(db, TABLE_RECORDS, "battery_temp_series", "TEXT")
            }
        }

        private fun addColumnIfMissing(
            db: SQLiteDatabase,
            table: String,
            column: String,
            type: String
        ) {
            val cursor = db.rawQuery("PRAGMA table_info($table)", null)
            val exists = cursor.use { c ->
                val nameIndex = c.getColumnIndex("name")
                if (nameIndex < 0) false else {
                    (0 until c.count).any {
                        c.moveToPosition(it)
                        c.getString(nameIndex) == column
                    }
                }
            }
            if (!exists) db.execSQL("ALTER TABLE $table ADD COLUMN $column $type")
        }

        fun insertSamples(samples: List<SessionSample>) {
            if (samples.isEmpty()) return
            val db = writableDatabase
            db.beginTransaction()
            try {
                for (sample in samples) {
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
                        put("thermal_status", sample.thermalStatus)
                        put("power_watts", sample.powerWatts)
                    }
                    db.insert(TABLE_SAMPLES, null, cv)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
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
                put("fps_series", JSONArray(record.fpsSeries.finiteDoubles()).toString())
                put("series_times_ms", JSONArray(record.seriesTimesMs.map { it }).toString())
                put("cpu_series", JSONArray(record.cpuSeries.finiteDoubles()).toString())
                put("temp_series", JSONArray(record.tempSeries.finiteDoubles()).toString())
                put("frame_time_series", JSONArray(record.frameTimeSeries.finiteDoubles()).toString())
                put("battery_series", JSONArray(record.batterySeries.finiteDoubles()).toString())
                put("peak_thermal_status", record.peakThermalStatus)
                put("throttled_seconds", record.throttledSeconds)
                put("thermal_status_series", JSONArray(record.thermalStatusSeries.finiteDoubles()).toString())
                put("jitter_ms", record.jitterMs)
                put("fps_variance", record.fpsVariance)
                put("avg_power_w", record.avgPowerW)
                put("cap_series", JSONArray(record.capSeries.finiteDoubles()).toString())
                put("cap_changes", JSONArray(record.capChanges.map { change ->
                    JSONObject().apply {
                        put("second", change.second)
                        put("fromFps", change.fromFps ?: JSONObject.NULL)
                        put("toFps", change.toFps)
                        put("thermalStatus", change.thermalStatus ?: JSONObject.NULL)
                        put("temperatureC", change.temperatureC?.toDouble() ?: JSONObject.NULL)
                        put("cause", change.cause)
                    }
                }).toString())
                put("thermal_cap_seconds", record.thermalCapSeconds)
                record.sceneSmoothnessPercent.finiteOrNull()?.let { put("scene_smoothness_percent", it) }
                put("device_model", record.device.model)
                put("device_platform", record.device.platform)
                put("device_resolution", record.device.resolution)
                put("device_os", record.device.osVersion)
                put("device_profile", record.device.profile)
                put("power_series", JSONArray(record.powerSeries.finiteDoubles()).toString())
                put("cpu_freq_series", JSONArray(record.cpuFreqSeries.finiteDoubles()).toString())
                put("gpu_freq_series", JSONArray(record.gpuFreqSeries.finiteDoubles()).toString())
                put("cpu_temp_series", JSONArray(record.cpuTempSeries.finiteDoubles()).toString())
                put("soc_temp_series", JSONArray(record.socTempSeries.finiteDoubles()).toString())
                put("battery_temp_series", JSONArray(record.batteryTempSeries.finiteDoubles()).toString())
                put("gpu_percent_series", JSONArray(record.gpuPercentSeries.finiteDoubles()).toString())
                put("cpu_group_names", JSONArray(record.cpuGroupNames).toString())
                put("cpu_group_series", record.cpuGroupSeries.toNullableJsonArrays().toString())
                put("cpu_group_freq_series", record.cpuGroupFreqSeries.toNullableJsonArrays().toString())
                put("per_core_load_series", record.perCoreLoadSeries.toNullableJsonArrays().toString())
                put("per_core_freq_series", record.perCoreFreqSeries.toNullableIntJsonArrays().toString())
                put("ddr_freq_series", JSONArray(record.ddrFreqSeries).toString())
                put("thread_names", JSONArray(record.threadNames).toString())
                put("thread_avg_loads", JSONArray(record.threadAvgLoads.finiteDoubles()).toString())
                put("thread_max_loads", JSONArray(record.threadMaxLoads.finiteDoubles()).toString())
                put("avg_current_ma", record.avgCurrentMa.finiteOrNull())
                put("voltage_mv", record.voltageMv)
                put("battery_cycles", record.batteryCycles)
                put("shell_logs", record.shellLogs)
                put("note", record.note)
                put("display_hz", record.displayHz.finiteOrNull())
            }
            writableDatabase.insertWithOnConflict(
                TABLE_RECORDS,
                null,
                cv,
                SQLiteDatabase.CONFLICT_REPLACE
            )
        }

        fun updateNote(id: String, note: String) {
            val cv = ContentValues().apply { put("note", note) }
            writableDatabase.update(TABLE_RECORDS, cv, "id = ?", arrayOf(id))
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
                    val peakStatusIdx = c.getColumnIndex("peak_thermal_status")
                    val throttledIdx = c.getColumnIndex("throttled_seconds")
                    val statusSeriesIdx = c.getColumnIndex("thermal_status_series")
                    val gapIdx = c.getColumnIndex("gap_seconds")
                    val timesIdx = c.getColumnIndex("series_times_ms")
                    val jitterIdx = c.getColumnIndex("jitter_ms")
                    val varianceIdx = c.getColumnIndex("fps_variance")
                    val powerIdx = c.getColumnIndex("avg_power_w")
                    val capSeriesIdx = c.getColumnIndex("cap_series")
                    val capChangesIdx = c.getColumnIndex("cap_changes")
                    val thermalCapIdx = c.getColumnIndex("thermal_cap_seconds")
                    val smoothnessIdx = c.getColumnIndex("scene_smoothness_percent")
                    val deviceModelIdx = c.getColumnIndex("device_model")
                    val devicePlatformIdx = c.getColumnIndex("device_platform")
                    val deviceResolutionIdx = c.getColumnIndex("device_resolution")
                    val deviceOsIdx = c.getColumnIndex("device_os")
                    val deviceProfileIdx = c.getColumnIndex("device_profile")
                    val powerSeriesIdx = c.getColumnIndex("power_series")
                    val cpuFreqSeriesIdx = c.getColumnIndex("cpu_freq_series")
                    val gpuFreqSeriesIdx = c.getColumnIndex("gpu_freq_series")
                    val cpuTempSeriesIdx = c.getColumnIndex("cpu_temp_series")
                    val socTempSeriesIdx = c.getColumnIndex("soc_temp_series")
                    val batteryTempSeriesIdx = c.getColumnIndex("battery_temp_series")
                    val gpuPercentSeriesIdx = c.getColumnIndex("gpu_percent_series")
                    val cpuGroupNamesIdx = c.getColumnIndex("cpu_group_names")
                    val cpuGroupSeriesIdx = c.getColumnIndex("cpu_group_series")
                    val cpuGroupFreqSeriesIdx = c.getColumnIndex("cpu_group_freq_series")
                    val perCoreLoadIdx = c.getColumnIndex("per_core_load_series")
                    val perCoreFreqIdx = c.getColumnIndex("per_core_freq_series")
                    val ddrFreqIdx = c.getColumnIndex("ddr_freq_series")
                    val threadNamesIdx = c.getColumnIndex("thread_names")
                    val threadAvgIdx = c.getColumnIndex("thread_avg_loads")
                    val threadMaxIdx = c.getColumnIndex("thread_max_loads")
                    val currentIdx = c.getColumnIndex("avg_current_ma")
                    val voltageIdx = c.getColumnIndex("voltage_mv")
                    val cyclesIdx = c.getColumnIndex("battery_cycles")
                    val shellLogsIdx = c.getColumnIndex("shell_logs")
                    val noteIdx = c.getColumnIndex("note")
                    val displayHzIdx = c.getColumnIndex("display_hz")
                    list.add(
                        SessionRecord(
                            id = c.getString(c.getColumnIndexOrThrow("id")),
                            appPackage = c.getString(c.getColumnIndexOrThrow("app_package")),
                            appLabel = c.getString(c.getColumnIndexOrThrow("app_label")),
                            startedAt = c.getLong(c.getColumnIndexOrThrow("started_at")),
                            durationMs = c.getLong(c.getColumnIndexOrThrow("duration_ms")),
                            avgFps = c.getFloat(c.getColumnIndexOrThrow("avg_fps")).finiteOrZero(),
                            maxFps = c.getFloat(c.getColumnIndexOrThrow("max_fps")).finiteOrZero(),
                            minFps = c.getFloat(c.getColumnIndexOrThrow("min_fps")).finiteOrZero(),
                            low1Percent = c.getFloat(c.getColumnIndexOrThrow("low1_percent")).finiteOrZero(),
                            low5Percent = c.getFloat(c.getColumnIndexOrThrow("low5_percent")).finiteOrZero(),
                            smoothness = c.getFloat(c.getColumnIndexOrThrow("smoothness")).finiteOrZero(),
                            totalFrames = c.getInt(c.getColumnIndexOrThrow("total_frames")),
                            jankFrames = c.getInt(c.getColumnIndexOrThrow("jank_frames")),
                            avgFrameTimeMs = c.getFloat(c.getColumnIndexOrThrow("avg_frame_time_ms")).finiteOrZero(),
                            maxFrameTimeMs = c.getFloat(c.getColumnIndexOrThrow("max_frame_time_ms")).finiteOrZero(),
                            maxTempC = if (maxTempIdx >= 0 && !c.isNull(maxTempIdx)) c.getFloat(maxTempIdx).finiteOrNull() else null,
                            avgCpu = if (avgCpuIdx >= 0 && !c.isNull(avgCpuIdx)) c.getFloat(avgCpuIdx).finiteOrNull() else null,
                            fpsSeries = jsonToFloatList(c.getString(c.getColumnIndexOrThrow("fps_series"))),
                            seriesTimesMs = if (timesIdx >= 0) {
                                jsonToLongList(c.getString(timesIdx))
                            } else {
                                emptyList()
                            },
                            cpuSeries = jsonToFloatList(c.getString(c.getColumnIndexOrThrow("cpu_series"))),
                            tempSeries = jsonToFloatList(c.getString(c.getColumnIndexOrThrow("temp_series"))),
                            frameTimeSeries = jsonToFloatList(c.getString(c.getColumnIndexOrThrow("frame_time_series"))),
                            batterySeries = jsonToFloatList(c.getString(c.getColumnIndexOrThrow("battery_series"))),
                            longestFrameMs = c.getFloat(c.getColumnIndexOrThrow("longest_frame_ms")).finiteOrZero(),
                            dropSeconds = c.getInt(c.getColumnIndexOrThrow("drop_seconds")),
                            gapSeconds = if (gapIdx >= 0) c.getInt(gapIdx) else 0,
                            peakThermalStatus = if (peakStatusIdx >= 0 && !c.isNull(peakStatusIdx)) {
                                c.getInt(peakStatusIdx)
                            } else null,
                            throttledSeconds = if (throttledIdx >= 0) c.getInt(throttledIdx) else 0,
                            thermalStatusSeries = if (statusSeriesIdx >= 0) {
                                jsonToFloatList(c.getString(statusSeriesIdx))
                            } else emptyList(),
                            jitterMs = if (jitterIdx >= 0 && !c.isNull(jitterIdx)) c.getFloat(jitterIdx).finiteOrNull() else null,
                            fpsVariance = if (varianceIdx >= 0 && !c.isNull(varianceIdx)) c.getFloat(varianceIdx).finiteOrNull() else null,
                            avgPowerW = if (powerIdx >= 0 && !c.isNull(powerIdx)) c.getFloat(powerIdx).finiteOrNull() else null,
                            capSeries = if (capSeriesIdx >= 0) {
                                jsonToFloatList(c.getString(capSeriesIdx))
                            } else emptyList(),
                            capChanges = if (capChangesIdx >= 0) {
                                runCatching { JSONArray(c.getString(capChangesIdx)).toCapChanges() }
                                    .getOrDefault(emptyList<CapChange>())
                            } else emptyList(),
                            thermalCapSeconds = if (thermalCapIdx >= 0) c.getInt(thermalCapIdx) else 0,
                            sceneSmoothnessPercent = if (smoothnessIdx >= 0 && !c.isNull(smoothnessIdx)) {
                                c.getFloat(smoothnessIdx).finiteOrNull()
                            } else null,
                            device = SessionDeviceInfo(
                                model = if (deviceModelIdx >= 0) c.getString(deviceModelIdx) else null,
                                platform = if (devicePlatformIdx >= 0) c.getString(devicePlatformIdx) else null,
                                resolution = if (deviceResolutionIdx >= 0) c.getString(deviceResolutionIdx) else null,
                                osVersion = if (deviceOsIdx >= 0) c.getString(deviceOsIdx) else null,
                                profile = if (deviceProfileIdx >= 0) c.getString(deviceProfileIdx) else null
                            ),
                            powerSeries = if (powerSeriesIdx >= 0) {
                                jsonToFloatList(c.getString(powerSeriesIdx))
                            } else emptyList(),
                            cpuFreqSeries = if (cpuFreqSeriesIdx >= 0) {
                                jsonToFloatList(c.getString(cpuFreqSeriesIdx))
                            } else emptyList(),
                            gpuFreqSeries = if (gpuFreqSeriesIdx >= 0) {
                                jsonToFloatList(c.getString(gpuFreqSeriesIdx))
                            } else emptyList(),
                            cpuTempSeries = if (cpuTempSeriesIdx >= 0) {
                                jsonToFloatList(c.getString(cpuTempSeriesIdx))
                            } else emptyList(),
                            socTempSeries = if (socTempSeriesIdx >= 0) {
                                jsonToFloatList(c.getString(socTempSeriesIdx))
                            } else emptyList(),
                            batteryTempSeries = if (batteryTempSeriesIdx >= 0) {
                                jsonToFloatList(c.getString(batteryTempSeriesIdx))
                            } else emptyList(),
                            gpuPercentSeries = if (gpuPercentSeriesIdx >= 0) {
                                jsonToFloatList(c.getString(gpuPercentSeriesIdx))
                            } else emptyList(),
                            cpuGroupNames = if (cpuGroupNamesIdx >= 0) {
                                runCatching {
                                    val arr = JSONArray(c.getString(cpuGroupNamesIdx))
                                    (0 until arr.length()).mapNotNull { i ->
                                        arr.optString(i).takeIf { it.isNotBlank() }
                                    }
                                }.getOrDefault(emptyList())
                            } else emptyList(),
                            cpuGroupSeries = if (cpuGroupSeriesIdx >= 0) {
                                runCatching {
                                    JSONArray(c.getString(cpuGroupSeriesIdx)).toNullableFloatLists()
                                }.getOrDefault(emptyList())
                            } else emptyList(),
                            cpuGroupFreqSeries = if (cpuGroupFreqSeriesIdx >= 0) {
                                runCatching {
                                    JSONArray(c.getString(cpuGroupFreqSeriesIdx)).toNullableFloatLists()
                                }.getOrDefault(emptyList())
                            } else emptyList(),
                            perCoreLoadSeries = if (perCoreLoadIdx >= 0) {
                                runCatching {
                                    JSONArray(c.getString(perCoreLoadIdx)).toNullableFloatLists()
                                }.getOrDefault(emptyList())
                            } else emptyList(),
                            perCoreFreqSeries = if (perCoreFreqIdx >= 0) {
                                runCatching {
                                    JSONArray(c.getString(perCoreFreqIdx)).toNullableIntLists()
                                }.getOrDefault(emptyList())
                            } else emptyList(),
                            ddrFreqSeries = if (ddrFreqIdx >= 0) {
                                jsonToFloatList(c.getString(ddrFreqIdx)).map { it.toInt() }
                            } else emptyList(),
                            threadNames = if (threadNamesIdx >= 0) {
                                runCatching {
                                    val arr = JSONArray(c.getString(threadNamesIdx))
                                    (0 until arr.length()).mapNotNull { i ->
                                        arr.optString(i).takeIf { it.isNotBlank() }
                                    }
                                }.getOrDefault(emptyList())
                            } else emptyList(),
                            threadAvgLoads = if (threadAvgIdx >= 0) {
                                jsonToFloatList(c.getString(threadAvgIdx))
                            } else emptyList(),
                            threadMaxLoads = if (threadMaxIdx >= 0) {
                                jsonToFloatList(c.getString(threadMaxIdx))
                            } else emptyList(),
                            avgCurrentMa = if (currentIdx >= 0 && !c.isNull(currentIdx)) {
                                c.getFloat(currentIdx).finiteOrNull()
                            } else null,
                            voltageMv = if (voltageIdx >= 0 && !c.isNull(voltageIdx)) {
                                c.getInt(voltageIdx).takeIf { it > 0 }
                            } else null,
                            batteryCycles = if (cyclesIdx >= 0 && !c.isNull(cyclesIdx)) {
                                c.getInt(cyclesIdx).takeIf { it >= 0 }
                            } else null,
                            shellLogs = if (shellLogsIdx >= 0) c.getString(shellLogsIdx).orEmpty() else "",
                            note = if (noteIdx >= 0) c.getString(noteIdx).orEmpty() else "",
                            displayHz = if (displayHzIdx >= 0 && !c.isNull(displayHzIdx)) {
                                c.getFloat(displayHzIdx).finiteOrNull()
                            } else null
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

        private fun jsonToLongList(json: String?): List<Long> {
            if (json.isNullOrBlank()) return emptyList()
            val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
            val list = ArrayList<Long>(arr.length())
            for (i in 0 until arr.length()) {
                val value = arr.optLong(i, 0L)
                if (value >= 0L) list.add(value)
            }
            return list
        }

        private fun jsonToFloatList(json: String?): List<Float> {
            if (json.isNullOrBlank()) return emptyList()
            val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
            val list = ArrayList<Float>(arr.length())
            for (i in 0 until arr.length()) {
                val value = arr.optDouble(i, 0.0).toFloat()
                if (value.finiteOrNull() != null) list.add(value)
            }
            return list
        }

        companion object {
            private const val DB_NAME = "framescope_sessions.db"
            private const val DB_VERSION = 12
            private const val TABLE_RECORDS = "session_records"
            private const val TABLE_SAMPLES = "session_samples"
        }
    }

    companion object {
        const val MAX_RECORDS = 100
    }
}
