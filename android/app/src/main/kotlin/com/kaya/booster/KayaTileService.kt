package com.kaya.booster

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick-settings tile: one tap to arm or disarm the boost session.
 *
 * State comes from [KayaState] (prefs-backed), so the tile is honest after a
 * process restart or boot, and stays in sync with the widget and the app.
 * The subtitle mirrors the same wording as the home-screen widget.
 */
class KayaTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val wantOn = !KayaState.boostOn && !KayaState.engineOn
        if (wantOn) {
            val vpnPrepared = runCatching {
                android.net.VpnService.prepare(applicationContext) == null
            }.getOrDefault(false)
            if (vpnPrepared && !KayaState.engineOn) {
                if (KayaVpnService.start(applicationContext)) KayaState.update(engine = true)
            }
            if (!KayaState.boostOn && KayaBoostService.start(applicationContext)) {
                KayaState.update(boost = true)
            }
        } else {
            KayaVpnService.stop(applicationContext)
            KayaBoostService.stop(applicationContext)
            KayaState.update(engine = false, boost = false)
        }
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val engineOn = KayaState.engineOn
        val boostOn = KayaState.boostOn
        tile.state = when {
            engineOn || boostOn -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.subtitle = when {
            engineOn -> getString(R.string.widget_sub_engaged)
            boostOn -> getString(R.string.widget_sub_boost_only)
            else -> getString(R.string.widget_sub_off)
        }
        tile.updateTile()
    }
}
