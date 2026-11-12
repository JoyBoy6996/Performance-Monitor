package com.framescope.monitor.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AccessMode { AUTO, SHIZUKU, ADB, ROOT }
enum class OverlayStyle { PILL, COMPACT, CLASSIC, DETAILED, RTSS }
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** A single persisted setting exposed as a [StateFlow]. */
class Pref<T> internal constructor(
    private val sp: SharedPreferences,
    private val key: String,
    private val default: T
) {
    private val _flow = MutableStateFlow(read(sp, key, default))
    val flow: StateFlow<T> = _flow.asStateFlow()

    var value: T
        get() = _flow.value
        set(v) {
            _flow.value = v
            write(sp, key, v)
        }

    fun reset() {
        value = default
    }

    /** Re-reads the stored value, used after a backup is restored. */
    fun reload() {
        _flow.value = read(sp, key, default)
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        internal fun <T> read(sp: SharedPreferences, key: String, default: T): T = when (default) {
            is Boolean -> sp.getBoolean(key, default) as T
            is Int -> sp.getInt(key, default) as T
            is Float -> sp.getFloat(key, default) as T
            is Long -> sp.getLong(key, default) as T
            is String -> (sp.getString(key, default) ?: default) as T
            else -> default
        }

        internal fun <T> write(sp: SharedPreferences, key: String, value: T) {
            when (value) {
                is Boolean -> sp.edit().putBoolean(key, value).apply()
                is Int -> sp.edit().putInt(key, value).apply()
                is Float -> sp.edit().putFloat(key, value).apply()
                is Long -> sp.edit().putLong(key, value).apply()
                is String -> sp.edit().putString(key, value).apply()
                else -> Unit
            }
        }
    }
}

/** All app settings, backed by SharedPreferences. */
class AppSettings(context: Context) {

    private val sp = context.getSharedPreferences("framescope", Context.MODE_PRIVATE)

    val accessMode = Pref(sp, "access_mode", AccessMode.AUTO.name)
    val adbHost = Pref(sp, "adb_host", "127.0.0.1")
    val adbPort = Pref(sp, "adb_port", 5555)

    /**
     * Repairs the ADB bridge by itself after a reboot.
     *
     * Needs root or Shizuku, because switching the phone's daemon into TCP
     * mode is something the shell identity is allowed to do.
     */
    val keepAdbBridge = Pref(sp, "keep_adb_bridge", true)

    /**
     * The master switch for the measurement.
     *
     * With it off nothing is sampled and no overlay is drawn, so the app costs
     * nothing and the user can tell at a glance that it is not running.
     */
    val measurementEnabled = Pref(sp, "measurement_enabled", true)

    /**
     * True once a privileged backend has worked.
     *
     * The setup is a one-time thing: the phone remembers the key, so the app
     * must not ask for the setup again after the first success.
     */
    val setupCompleted = Pref(sp, "setup_completed", false)

    val overlayEnabled = Pref(sp, "overlay_enabled", false)
    val overlayStyle = Pref(sp, "overlay_style", OverlayStyle.CLASSIC.name)
    val overlayRows = Pref(sp, "overlay_rows", "FPS,FRAME,CPU,GPU,TEMP,BAT,MEM")
    val overlayOpacity = Pref(sp, "overlay_opacity", 0.88f)
    val overlayScale = Pref(sp, "overlay_scale", 1.0f)
    val overlayX = Pref(sp, "overlay_x", 0.03f)
    val overlayY = Pref(sp, "overlay_y", 0.14f)
    val overlayShowGraph = Pref(sp, "overlay_graph", true)
    val overlayGraphHeight = Pref(sp, "overlay_graph_h", 54)
    val overlayClickThrough = Pref(sp, "overlay_click_through", false)

    val protectBankApps = Pref(sp, "protect_bank_apps", true)
    val protectedApps = Pref(sp, "protected_apps", "")

    val fpsThreshold = Pref(sp, "fps_threshold", 45)
    val sampleIntervalMs = Pref(sp, "sample_interval", 400)
    val autoRecord = Pref(sp, "auto_record", false)
    val monitorWhileBackground = Pref(sp, "monitor_background", true)

    val themeMode = Pref(sp, "theme_mode", ThemeMode.SYSTEM.name)
    val dynamicColor = Pref(sp, "dynamic_color", true)

    fun protectedPackages(): Set<String> =
        protectedApps.value.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun setProtectedPackages(packages: Set<String>) {
        protectedApps.value = packages.joinToString(",")
    }

    /**
     * Every stored preference as JSON, with the type of each value kept intact so a backup
     * restores floats as floats and not as doubles.
     */
    fun exportAll(): JSONObject {
        val out = JSONObject()
        sp.all.forEach { (key, value) ->
            val entry = JSONObject()
            when (value) {
                is Boolean -> { entry.put("t", "b"); entry.put("v", value) }
                is Int -> { entry.put("t", "i"); entry.put("v", value) }
                is Float -> { entry.put("t", "f"); entry.put("v", value.toDouble()) }
                is Long -> { entry.put("t", "l"); entry.put("v", value) }
                is String -> { entry.put("t", "s"); entry.put("v", value) }
                else -> return@forEach
            }
            out.put(key, entry)
        }
        return out
    }

    /** Restores preferences produced by [exportAll] and refreshes the live values. */
    fun importAll(json: JSONObject) {
        val editor = sp.edit()
        json.keys().forEach { key ->
            val entry = json.optJSONObject(key) ?: return@forEach
            when (entry.optString("t")) {
                "b" -> editor.putBoolean(key, entry.optBoolean("v"))
                "i" -> editor.putInt(key, entry.optInt("v"))
                "f" -> editor.putFloat(key, entry.optDouble("v", 0.0).toFloat())
                "l" -> editor.putLong(key, entry.optLong("v"))
                "s" -> editor.putString(key, entry.optString("v"))
            }
        }
        editor.apply()
        refresh()
    }

    /** Re-reads every setting from disk, so a restored backup takes effect at once. */
    fun refresh() {
        allPrefs().forEach { it.reload() }
    }

    private fun allPrefs(): List<Pref<*>> = listOf(
        accessMode, adbHost, adbPort,
        overlayEnabled, overlayStyle, overlayRows, overlayOpacity, overlayScale,
        overlayX, overlayY, overlayShowGraph, overlayGraphHeight, overlayClickThrough,
        protectBankApps, protectedApps,
        fpsThreshold, sampleIntervalMs, autoRecord, monitorWhileBackground,
        themeMode, dynamicColor
    )
}
