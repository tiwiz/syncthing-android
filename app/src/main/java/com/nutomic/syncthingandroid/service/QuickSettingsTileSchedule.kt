package com.nutomic.syncthingandroid.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.service.RunConditionMonitor.ACTION_SYNC_TRIGGER_FIRED
import com.nutomic.syncthingandroid.service.RunConditionMonitor.EXTRA_BEGIN_ACTIVE_TIME_WINDOW

/** Starts Syncthing for the configured duration, when it follows a time schedule. */
@RequiresApi(Build.VERSION_CODES.N)
class QuickSettingsTileSchedule :
    TileService(), ServiceConnection, SyncthingService.OnServiceStateChangeListener {

    private var tilesAvailableState = Tile.STATE_INACTIVE
    private var appContext: Context? = null
    // Initialized when the tile starts listening; no injection needed.
    private var preferences: SharedPreferences? = null
    private var syncthingService: SyncthingService? = null

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, pref ->
        if (pref == Constants.PREF_BTNSTATE_FORCE_START_STOP) {
            refreshTile()
        }
    }

    override fun onDestroy() {
        logV("onDestroy()")
        syncthingService?.unregisterOnServiceStateChangeListener(this)
        syncthingService = null
        preferences?.unregisterOnSharedPreferenceChangeListener(prefListener)
        try {
            appContext?.unbindService(this)
        } catch (_: IllegalArgumentException) {
            logV("Service not bound or already unbound")
        } catch (_: IllegalStateException) {
            logV("Service not bound or already unbound")
        }
        super.onDestroy()
    }

    override fun onStartListening() {
        logV("onStartListening()")
        if (qsTile != null) {
            val context = application.applicationContext
            appContext = context
            preferences =
                PreferenceManager.getDefaultSharedPreferences(context).also {
                    it.registerOnSharedPreferenceChangeListener(prefListener)
                }
            try {
                context.bindService(
                    Intent(context, SyncthingService::class.java),
                    this,
                    BIND_AUTO_CREATE,
                )
            } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
                // Binding is best effort; the tile then only shows the schedule's state.
                Log.w(TAG, "Failed to bind to SyncthingService", e)
            }
            refreshTile()
        }
        super.onStartListening()
    }

    override fun onClick() {
        if (qsTile.state == Tile.STATE_UNAVAILABLE) {
            return
        }
        LocalBroadcastManager.getInstance(checkNotNull(appContext))
            .sendBroadcast(
                Intent(ACTION_SYNC_TRIGGER_FIRED).putExtra(EXTRA_BEGIN_ACTIVE_TIME_WINDOW, true)
            )
    }

    private fun refreshTile() {
        if (setTileUnavailable()) {
            return
        }
        updateTile(tilesAvailableState)
    }

    /**
     * Disables the tile if the app is not running, the schedule is off, or Syncthing is forced to
     * run or stop. Returns whether it did.
     */
    private fun setTileUnavailable(): Boolean {
        if (qsTile == null) return false
        val prefs = checkNotNull(preferences)
        val available =
            isSyncthingServiceRunning(this) &&
                prefs.getBoolean(Constants.PREF_RUN_ON_TIME_SCHEDULE, false) &&
                prefs.getInt(
                    Constants.PREF_BTNSTATE_FORCE_START_STOP,
                    Constants.BTNSTATE_NO_FORCE_START_STOP,
                ) == Constants.BTNSTATE_NO_FORCE_START_STOP
        if (available) {
            return false
        }
        updateTile(Tile.STATE_UNAVAILABLE)
        return true
    }

    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        logV("onServiceConnected(ComponentName=$name, IBinder=$service)")
        syncthingService =
            (service as SyncthingServiceBinder).service.also {
                it.registerOnServiceStateChangeListener(this)
            }
    }

    override fun onServiceDisconnected(componentName: ComponentName) {
        syncthingService = null
    }

    private fun updateTile(newState: Int) {
        val tile = qsTile ?: return
        val res = checkNotNull(appContext).resources
        val label =
            if (newState == Tile.STATE_INACTIVE || newState == Tile.STATE_ACTIVE) {
                val minutes =
                    checkNotNull(preferences).getString(Constants.PREF_SYNC_DURATION_MINUTES, "5")
                res.getString(
                    R.string.qs_schedule_label_minutes,
                    Integer.parseInt(checkNotNull(minutes)),
                )
            } else {
                res.getString(R.string.qs_schedule_disabled)
            }
        // Compare the label too: a new tile starts INACTIVE with the manifest label, which does not
        // know the configured duration.
        if (newState == tile.state && label.contentEquals(tile.label ?: "")) return
        tile.state = newState
        tile.label = label
        tile.updateTile()
    }

    override fun onServiceStateChange(currentState: SyncthingService.State) {
        logV("onServiceStateChange: $currentState")
        tilesAvailableState =
            if (
                currentState == SyncthingService.State.STARTING ||
                    currentState == SyncthingService.State.ACTIVE
            ) {
                Tile.STATE_ACTIVE
            } else {
                Tile.STATE_INACTIVE
            }
        refreshTile()
    }

    private fun logV(logMessage: String) {
        if (ENABLE_VERBOSE_LOG) Log.v(TAG, logMessage)
    }

    private companion object {
        const val TAG = "QuickSettingsTileSchedule"
        const val ENABLE_VERBOSE_LOG = false
    }
}
