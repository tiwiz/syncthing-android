package com.nutomic.syncthingandroid.service

import android.app.ActivityManager
import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import android.service.quicksettings.Tile
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.activities.PhotoShootActivity
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import com.nutomic.syncthingandroid.testutil.StoppedSyncthingService
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The three quick settings tiles: force start/stop, timed sync and camera. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-rUS", shadows = [ShadowEnvironmentWithAllFilesAccess::class])
class QuickSettingsTilesTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val prefs = PreferenceManager.getDefaultSharedPreferences(app)
    private val localBroadcasts = mutableListOf<Intent>()

    @Before
    fun setUp() {
        listOf(
                RunConditionMonitor.ACTION_UPDATE_SHOULDRUN_DECISION,
                RunConditionMonitor.ACTION_SYNC_TRIGGER_FIRED,
            )
            .forEach { action ->
                LocalBroadcastManager.getInstance(app)
                    .registerReceiver(
                        object : BroadcastReceiver() {
                            override fun onReceive(context: Context, intent: Intent) {
                                localBroadcasts += intent
                            }
                        },
                        IntentFilter(action),
                    )
            }
    }

    private fun syncthingRunning(running: Boolean) {
        val services =
            if (running) {
                listOf(
                    ActivityManager.RunningServiceInfo().apply {
                        service = ComponentName(app, SyncthingService::class.java)
                    }
                )
            } else {
                emptyList()
            }
        shadowOf(app.getSystemService(ActivityManager::class.java)).setServices(services)
    }

    private fun forceState(state: Int) {
        prefs.edit().putInt(Constants.PREF_BTNSTATE_FORCE_START_STOP, state).commit()
    }

    private fun Tile.summary() = "$label/${stateName(state)}"

    private fun stateName(state: Int) =
        when (state) {
            Tile.STATE_ACTIVE -> "active"
            Tile.STATE_INACTIVE -> "inactive"
            Tile.STATE_UNAVAILABLE -> "unavailable"
            else -> "state $state"
        }

    @Test
    fun `the force tile is unavailable while Syncthing's service is not running`() {
        syncthingRunning(false)
        val tile = Robolectric.buildService(QuickSettingsTileForce::class.java).create().get()

        tile.onStartListening()

        assertEquals(Tile.STATE_UNAVAILABLE, tile.qsTile.state)
    }

    @Test
    fun `the force tile shows the force state and cycles through it on click`() {
        syncthingRunning(true)
        forceState(Constants.BTNSTATE_NO_FORCE_START_STOP)
        val tile = Robolectric.buildService(QuickSettingsTileForce::class.java).create().get()
        tile.onStartListening()

        val seen = mutableListOf(tile.qsTile.summary())
        repeat(3) {
            tile.onClick()
            seen += tile.qsTile.summary()
        }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            listOf(
                "Following run conditions/inactive",
                "Forced to run/active",
                "Forced to stop/active",
                "Following run conditions/inactive",
            ),
            seen,
        )
        assertEquals(
            Constants.BTNSTATE_NO_FORCE_START_STOP,
            prefs.getInt(Constants.PREF_BTNSTATE_FORCE_START_STOP, -1),
        )
        assertEquals(
            3,
            localBroadcasts.count {
                it.action == RunConditionMonitor.ACTION_UPDATE_SHOULDRUN_DECISION
            },
        )
    }

    @Test
    fun `the schedule tile needs the service running, the time schedule on and no force`() {
        StoppedSyncthingService.install()
        val cases =
            listOf(
                Triple(false, true, Constants.BTNSTATE_NO_FORCE_START_STOP),
                Triple(true, false, Constants.BTNSTATE_NO_FORCE_START_STOP),
                Triple(true, true, Constants.BTNSTATE_FORCE_START),
                Triple(true, true, Constants.BTNSTATE_NO_FORCE_START_STOP),
            )
        val results = cases.map { (running, schedule, force) ->
            syncthingRunning(running)
            prefs.edit().putBoolean(Constants.PREF_RUN_ON_TIME_SCHEDULE, schedule).commit()
            forceState(force)
            val tile =
                Robolectric.buildService(QuickSettingsTileSchedule::class.java).create().get()
            tile.onStartListening()
            shadowOf(Looper.getMainLooper()).idle()
            tile.qsTile.summary()
        }

        assertEquals(
            listOf(
                "Schedule disabled/unavailable",
                "Schedule disabled/unavailable",
                "Schedule disabled/unavailable",
                // CHARACTERIZATION: available and inactive, but the label is not set (see below).
                "null/inactive",
            ),
            results,
        )
    }

    @Test
    fun `the schedule tile starts a timed run on click, but not while unavailable`() {
        StoppedSyncthingService.install()
        syncthingRunning(true)
        prefs
            .edit()
            .putBoolean(Constants.PREF_RUN_ON_TIME_SCHEDULE, true)
            .putString(Constants.PREF_SYNC_DURATION_MINUTES, "15")
            .commit()
        val tile = Robolectric.buildService(QuickSettingsTileSchedule::class.java).create().get()
        tile.onStartListening()
        shadowOf(Looper.getMainLooper()).idle()
        // CHARACTERIZATION: updateTile returns early when the state does not change, and a new tile
        // is already INACTIVE, so the label stays the manifest's "Run for 5 minutes" (null here)
        // instead of the configured 15 minutes until the tile goes through another state.
        assertEquals("null/inactive", tile.qsTile.summary())

        tile.onClick()
        tile.qsTile.state = Tile.STATE_UNAVAILABLE
        tile.onClick()
        shadowOf(Looper.getMainLooper()).idle()

        val triggers = localBroadcasts.filter {
            it.action == RunConditionMonitor.ACTION_SYNC_TRIGGER_FIRED
        }
        assertEquals(1, triggers.size)
        assertEquals(
            true,
            triggers
                .single()
                .getBooleanExtra(RunConditionMonitor.EXTRA_BEGIN_ACTIVE_TIME_WINDOW, false),
        )
    }

    @Test
    fun `the camera tile is always active and opens the photo screen`() {
        val tile = Robolectric.buildService(QuickSettingsTileCamera::class.java).create().get()

        tile.onStartListening()
        tile.onClick()

        assertEquals(Tile.STATE_ACTIVE, tile.qsTile.state)
        val started = shadowOf(app).nextStartedActivity
        assertEquals(ComponentName(app, PhotoShootActivity::class.java), started.component)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, started.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
