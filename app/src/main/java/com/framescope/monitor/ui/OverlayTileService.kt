package com.framescope.monitor.ui

import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.framescope.monitor.FrameScopeApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Quick settings tile that toggles the FPS overlay. */
class OverlayTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listenJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        val container = (application as? FrameScopeApp)?.container ?: return
        listenJob?.cancel()
        listenJob = scope.launch {
            container.overlay.running.collect { active ->
                updateTile(active)
            }
        }
    }

    override fun onStopListening() {
        listenJob?.cancel()
        listenJob = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val container = (application as? FrameScopeApp)?.container ?: return
        if (!Settings.canDrawOverlays(this)) {
            updateTile(false)
            return
        }
        container.overlay.toggle()
        updateTile(container.overlay.running.value)
    }

    private fun updateTile(active: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }
}
