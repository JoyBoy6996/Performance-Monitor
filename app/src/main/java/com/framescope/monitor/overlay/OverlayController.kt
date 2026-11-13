package com.framescope.monitor.overlay

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Starts and stops the floating overlay service. */
class OverlayController(private val context: Context) {

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun start() {
        if (!Settings.canDrawOverlays(context)) {
            _running.value = false
            return
        }
        val intent = Intent(context, OverlayService::class.java)
        ContextCompat.startForegroundService(context, intent)
        _running.value = true
    }

    fun stop() {
        val intent = Intent(context, OverlayService::class.java)
        context.stopService(intent)
        _running.value = false
    }

    fun toggle() {
        if (_running.value) stop() else start()
    }

    internal fun syncRunning(active: Boolean) {
        _running.value = active
    }
}
