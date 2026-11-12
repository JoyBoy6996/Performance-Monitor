package com.framescope.monitor.ui

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.framescope.monitor.FrameScopeApp

/** Quick settings tile that toggles the FPS overlay. */
class OverlayTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val container = (application as? FrameScopeApp)?.container ?: return
        updateTile(container.overlay.running.value)
    }

    override fun onClick() {
        super.onClick()
        val container = (application as? FrameScopeApp)?.container ?: return
        container.overlay.toggle()
        updateTile(container.overlay.running.value)
    }

    private fun updateTile(active: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }
}
