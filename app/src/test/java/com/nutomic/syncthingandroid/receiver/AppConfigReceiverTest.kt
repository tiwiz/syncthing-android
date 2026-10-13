package com.nutomic.syncthingandroid.receiver

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.RunConditionMonitor
import com.nutomic.syncthingandroid.service.SyncthingService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * [AppConfigReceiver] lets other apps force Syncthing on or off, or back to the run conditions,
 * when "Service control by broadcast" is enabled.
 */
@RunWith(AndroidJUnit4::class)
class AppConfigReceiverTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val prefs = PreferenceManager.getDefaultSharedPreferences(app)
    private var decisionUpdates = 0

    @Before
    fun setUp() {
        LocalBroadcastManager.getInstance(app)
            .registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        decisionUpdates++
                    }
                },
                IntentFilter(RunConditionMonitor.ACTION_UPDATE_SHOULDRUN_DECISION),
            )
    }

    private fun controlByBroadcast(enabled: Boolean) {
        prefs.edit().putBoolean(Constants.PREF_BROADCAST_SERVICE_CONTROL, enabled).commit()
    }

    private fun receive(action: String) {
        AppConfigReceiver().onReceive(app, Intent("${app.packageName}.action.$action"))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun forceState(): Int? =
        if (prefs.contains(Constants.PREF_BTNSTATE_FORCE_START_STOP)) {
            prefs.getInt(Constants.PREF_BTNSTATE_FORCE_START_STOP, -1)
        } else {
            null
        }

    private fun startedService(): ComponentName? = shadowOf(app).nextStartedService?.component

    @Test
    fun `start forces Syncthing on and starts the service`() {
        controlByBroadcast(true)

        receive("START")

        assertEquals(Constants.BTNSTATE_FORCE_START, forceState())
        assertEquals(ComponentName(app, SyncthingService::class.java), startedService())
        assertEquals(1, decisionUpdates)
    }

    @Test
    fun `stop forces Syncthing off without starting the service`() {
        controlByBroadcast(true)

        receive("STOP")

        assertEquals(Constants.BTNSTATE_FORCE_STOP, forceState())
        assertEquals(null, startedService())
        assertEquals(1, decisionUpdates)
    }

    @Test
    fun `follow goes back to the run conditions and starts the service`() {
        controlByBroadcast(true)
        prefs
            .edit()
            .putInt(Constants.PREF_BTNSTATE_FORCE_START_STOP, Constants.BTNSTATE_FORCE_STOP)
            .commit()

        receive("FOLLOW")

        assertEquals(Constants.BTNSTATE_NO_FORCE_START_STOP, forceState())
        assertEquals(ComponentName(app, SyncthingService::class.java), startedService())
        assertEquals(1, decisionUpdates)
    }

    @Test
    fun `everything is ignored while control by broadcast is off`() {
        controlByBroadcast(false)

        listOf("START", "STOP", "FOLLOW").forEach { receive(it) }

        assertEquals(null, forceState())
        assertEquals(null, startedService())
        assertEquals(0, decisionUpdates)
    }

    @Test
    fun `unknown actions change nothing`() {
        controlByBroadcast(true)

        receive("RESTART")
        AppConfigReceiver().onReceive(app, Intent("org.example.action.START"))

        assertFalse(prefs.contains(Constants.PREF_BTNSTATE_FORCE_START_STOP))
        assertEquals(null, startedService())
        assertEquals(0, decisionUpdates)
    }

    @Test
    fun `the manifest exports it for the three actions`() {
        listOf("FOLLOW", "START", "STOP").forEach { action ->
            val receivers =
                app.packageManager.queryBroadcastReceivers(
                    Intent("${app.packageName}.action.$action").setPackage(app.packageName),
                    0,
                )
            assertEquals(
                action,
                listOf(AppConfigReceiver::class.java.name),
                receivers.map { it.activityInfo.name },
            )
            assertEquals(action, true, receivers.single().activityInfo.exported)
        }
    }
}
