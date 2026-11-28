package com.framescope.monitor.overlay

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Starts and stops the floating overlay service.
 *
 * @param onEnabled called whenever the overlay is turned on. The measurement
 * starts with it: a pill that appears with "-- FPS" on it is not a reading.
 * @param onDisabled called whenever the overlay is turned off. The measurement
 * goes with it: with the overlay gone there is nothing to show the frame rate
 * on, and a sampler that keeps asking the compositor for counters is work the
 * phone does for nothing.
 */
class OverlayController(
    private val context: Context,
    private val onEnabled: () -> Unit = {},
    private val onDisabled: () -> Unit = {}
) {

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun start() {
        // Starting an overlay that is already there would start the service a
        // second time and the measurement with it.
        if (_running.value) return
        val intent = Intent(context, OverlayService::class.java)
        ContextCompat.startForegroundService(context, intent)
        _running.value = true
        onEnabled()
    }

    fun stop() {
        val intent = Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_STOP)
        ContextCompat.startForegroundService(context, intent)
        _running.value = false
        // The state is cleared first: the measurement switch watches the
        // overlay, and it must see the overlay as already gone.
        onDisabled()
    }

    fun toggle() {
        if (_running.value) stop() else start()
    }
}
