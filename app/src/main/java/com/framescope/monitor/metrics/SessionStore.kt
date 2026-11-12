package com.framescope.monitor.metrics

import android.content.Context
import com.framescope.monitor.core.SessionRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Persists recorded sessions as JSON files inside the app data directory. */
class SessionStore(context: Context) {

    private val dir = File(context.filesDir, "sessions").apply { mkdirs() }
    private val _sessions = MutableStateFlow<List<SessionRecord>>(emptyList())
    val sessions: StateFlow<List<SessionRecord>> = _sessions.asStateFlow()

    init {
        load()
    }

    private fun load() {
        val files = dir.listFiles { f -> f.extension == "json" } ?: return
        val records = files.mapNotNull { runCatching { fromJson(it.readText()) }.getOrNull() }
            // Sessions without a single frame are leftovers from a build that
            // could not talk to the shell; they carry no information.
            .filter { it.totalFrames > 0 }
            .sortedByDescending { it.startedAt }
        _sessions.value = records
    }

    fun add(record: SessionRecord) {
        runCatching { File(dir, "${record.id}.json").writeText(toJson(record)) }
        _sessions.value = listOf(record) + _sessions.value
    }

    fun delete(id: String) {
        runCatching { File(dir, "$id.json").delete() }
        _sessions.value = _sessions.value.filterNot { it.id == id }
    }

    /** Writes a set of records to disk and refreshes the list. */
    fun importRecords(records: List<SessionRecord>) {
        records.forEach { runCatching { File(dir, "${it.id}.json").writeText(toJson(it)) } }
        load()
    }

    fun clear() {
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

    /** Parses a single session record. */
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
}
