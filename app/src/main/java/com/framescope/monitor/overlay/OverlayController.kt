package com.framescope.monitor.overlay

import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Starts and stops the floating overlay service. */
class OverlayController(private val context: Context) {

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun start() {
        val intent = Intent(context, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        _running.value = true
    }

    fun stop() {
        val intent = Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_STOP)
        context.startService(intent)
        _running.value = false
    }

    fun toggle() {
        if (_running.value) stop() else start()
    }
}
