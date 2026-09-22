package com.kaya.booster

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick-settings tile: one tap to arm or disarm the boost session. */
class KayaTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (KayaBoostServiceHolder.isRunning) {
            KayaBoostService.stop(applicationContext)
            KayaBoostServiceHolder.isRunning = false
        } else {
            KayaBoostService.start(applicationContext)
            KayaBoostServiceHolder.isRunning = true
        }
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        tile.state = if (KayaBoostServiceHolder.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }
}

object KayaBoostServiceHolder {
    var isRunning = false
}
