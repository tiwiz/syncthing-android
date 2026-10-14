package com.nutomic.syncthingandroid.service

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.service.RunConditionMonitor.Companion.ACTION_UPDATE_SHOULDRUN_DECISION

/** Cycles through following the run conditions, forcing Syncthing to run and forcing it to stop. */
@RequiresApi(Build.VERSION_CODES.N)
class QuickSettingsTileForce : TileService() {

    private lateinit var appContext: Context
    // Initialized when the tile starts listening; no injection needed.
    private lateinit var preferences: SharedPreferences

    override fun onStartListening() {
        val tile = qsTile
        if (tile != null) {
            appContext = application.applicationContext
            preferences = PreferenceManager.getDefaultSharedPreferences(appContext)
            // Disable the tile if the app is not running.
            if (!isSyncthingServiceRunning(this)) {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.updateTile()
                return
            }
            // Update tile to reflect the forced state.
            updateTileState(tile, forcedState())
        }
        super.onStartListening()
    }

    override fun onClick() {
        val tile = qsTile
        val newState =
            when (forcedState()) {
                Constants.BTNSTATE_FORCE_START -> Constants.BTNSTATE_FORCE_STOP
                Constants.BTNSTATE_NO_FORCE_START_STOP -> Constants.BTNSTATE_FORCE_START
                else -> Constants.BTNSTATE_NO_FORCE_START_STOP
            }
        preferences.edit { putInt(Constants.PREF_BTNSTATE_FORCE_START_STOP, newState) }
        LocalBroadcastManager.getInstance(appContext)
            .sendBroadcast(Intent(ACTION_UPDATE_SHOULDRUN_DECISION))
        updateTileState(tile, newState)
        tile.updateTile()
    }

    private fun forcedState(): Int =
        preferences.getInt(
            Constants.PREF_BTNSTATE_FORCE_START_STOP,
            Constants.BTNSTATE_NO_FORCE_START_STOP,
        )

    private fun updateTileState(tile: Tile, force: Int) {
        val (label, state, icon) =
            when (force) {
                Constants.BTNSTATE_FORCE_START ->
                    Triple(
                        R.string.qs_forced_to_run,
                        Tile.STATE_ACTIVE,
                        R.drawable.ic_qs_forced_to_run,
                    )
                Constants.BTNSTATE_FORCE_STOP ->
                    Triple(
                        R.string.qs_forced_to_stop,
                        Tile.STATE_ACTIVE,
                        R.drawable.ic_qs_forced_to_stop,
                    )
                else ->
                    Triple(
                        R.string.qs_following_run_conditions,
                        Tile.STATE_INACTIVE,
                        R.drawable.ic_qs_force,
                    )
            }
        tile.label = appContext.resources.getString(label)
        tile.state = state
        tile.icon = Icon.createWithResource(appContext, icon)
        tile.updateTile()
    }
}

/** Whether SyncthingService is among the running services. */
internal fun isSyncthingServiceRunning(context: Context): Boolean {
    val am = context.getSystemService(ActivityManager::class.java)
    @Suppress("DEPRECATION") // Still returns the app's own services.
    return am.getRunningServices(Int.MAX_VALUE).any {
        it.service.className == SyncthingService::class.java.name
    }
}
