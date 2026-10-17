package com.nutomic.syncthingandroid.service

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import com.nutomic.syncthingandroid.ApplicationScope
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.service.RunConditionMonitor.Companion.ACTION_SYNC_TRIGGER_FIRED
import com.nutomic.syncthingandroid.service.RunConditionMonitor.Companion.EXTRA_BEGIN_ACTIVE_TIME_WINDOW
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Starts Syncthing for the configured duration, when it follows a time schedule. */
@AndroidEntryPoint
class QuickSettingsTileSchedule : TileService() {

    @Inject lateinit var connection: SyncthingConnection

    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    /** Follows Syncthing's state while the tile listens. */
    private var stateJob: Job? = null

    private var tilesAvailableState = Tile.STATE_INACTIVE
    private var appContext: Context? = null
    // Initialized when the tile starts listening; no injection needed.
    private var preferences: SharedPreferences? = null

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, pref ->
        if (pref == Constants.PREF_BTNSTATE_FORCE_START_STOP) {
            refreshTile()
        }
    }

    override fun onDestroy() {
        logV("onDestroy()")
        stateJob?.cancel()
        preferences?.unregisterOnSharedPreferenceChangeListener(prefListener)
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
            stateJob?.cancel()
            stateJob = appScope.launch {
                connection.snapshot.collect { snapshot ->
                    snapshot.state?.let(::onServiceStateChange)
                }
            }
            refreshTile()
        }
        super.onStartListening()
    }

    override fun onStopListening() {
        logV("onStopListening()")
        // The connection lets go of the service a while after this.
        stateJob?.cancel()
        stateJob = null
        super.onStopListening()
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

    private fun onServiceStateChange(currentState: SyncthingService.State) {
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
