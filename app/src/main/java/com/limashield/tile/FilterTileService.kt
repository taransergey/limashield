package com.limashield.tile

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.limashield.R
import com.limashield.bus.ServiceBus
import com.limashield.core.FilterState
import com.limashield.service.LocationFilterService
import com.limashield.ui.MainActivity
import com.limashield.util.hasLocationPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Quick Settings tile: service on/off + short status (spec §5). */
class FilterTileService : TileService() {

    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        s.launch { ServiceBus.ui.collect { render() } }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
    }

    override fun onClick() {
        val ui = ServiceBus.ui.value
        if (ui.running) {
            LocationFilterService.stop(this)
        } else if (hasLocationPermission(this)) {
            try {
                LocationFilterService.start(this)
            } catch (e: Exception) {
                openApp()
            }
        } else {
            openApp()
        }
    }

    private fun render() {
        val tile = qsTile ?: return
        val ui = ServiceBus.ui.value
        tile.state = if (ui.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "LimaShield"
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = getString(if (ui.running) shortStateRes(ui.state) else R.string.tile_off)
        }
        tile.updateTile()
    }

    private fun shortStateRes(state: FilterState): Int = when (state) {
        FilterState.TRUSTED -> R.string.tile_trusted
        FilterState.SPOOFED -> R.string.tile_spoofed
        FilterState.RECOVERING -> R.string.tile_recovering
        FilterState.BLIND -> R.string.tile_blind
        FilterState.JAMMED -> R.string.tile_jammed
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, 2, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        fun requestUpdate(ctx: Context) {
            try {
                requestListeningState(ctx, ComponentName(ctx, FilterTileService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
