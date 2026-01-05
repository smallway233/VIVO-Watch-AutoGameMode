package top.smallway.gamewatch

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

// 作者：Smallway
// 日期：2026-01-05
class GamingSyncTileService : TileService() {

    private val TAG = "GamingSyncTileService"

    companion object {
        fun requestListeningState(context: Context) {
            try {
                requestListeningState(context, ComponentName(context, GamingSyncTileService::class.java))
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val isRunning = GamingSyncService.isServiceRunning
        if (isRunning) {
            // Stop Service
            val intent = Intent(this, GamingSyncService::class.java)
            stopService(intent)
            qsTile.state = Tile.STATE_INACTIVE
        } else {
            // Start Service
            val intent = Intent(this, GamingSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            qsTile.state = Tile.STATE_ACTIVE
        }
        qsTile.updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val isRunning = GamingSyncService.isServiceRunning
        tile.state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "GameWatch同步"
        // tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_name) // Use default or specific icon
        tile.updateTile()
    }
}
