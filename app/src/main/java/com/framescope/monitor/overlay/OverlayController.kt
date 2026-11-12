package com.framescope.monitor.overlay

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Starts and stops the floating overlay service. */
class OverlayController(private val context: Context) {

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun start() {
        val intent = Intent(context, OverlayService::class.java)
        ContextCompat.startForegroundService(context, intent)
        _running.value = true
    }

    fun stop() {
        val intent = Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_STOP)
        ContextCompat.startForegroundService(context, intent)
        _running.value = false
    }

    fun toggle() {
        if (_running.value) stop() else start()
    }
}
