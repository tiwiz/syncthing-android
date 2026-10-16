package com.nutomic.syncthingandroid.service

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.os.BatteryManager
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.appDependencies
import com.nutomic.syncthingandroid.testutil.ShadowContentResolverWithStatusListeners
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkInfo

/**
 * How [RunConditionMonitor] reacts over time: broadcasts and the sync setting re-decide, and the
 * time schedule opens and closes run windows through scheduled jobs.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-rUS", shadows = [ShadowContentResolverWithStatusListeners::class])
@Suppress("DEPRECATION") // The monitor uses NetworkInfo.
class RunConditionTimelineTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val jobs = context.getSystemService(JobScheduler::class.java)
    private val decisions = mutableListOf<Boolean>()
    private var monitor: RunConditionMonitor? = null

    @Before
    fun setUp() {
        // LocalBroadcastManager is a process-wide singleton that would keep an earlier test's
        // context, and so its job scheduler.
        LocalBroadcastManager::class.java.getDeclaredField("mInstance").apply {
            isAccessible = true
            set(null, null)
        }
        ContentResolver.setMasterSyncAutomatically(true)
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsPowerSaveMode(false)
        SystemClock.setCurrentTimeMillis(HOURS_3)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofHours(3))
    }

    @After
    fun tearDown() {
        monitor?.shutdown()
    }

    private fun network(type: Int?) {
        val info = type?.let {
            ShadowNetworkInfo.newInstance(
                NetworkInfo.DetailedState.CONNECTED,
                it,
                0,
                true,
                NetworkInfo.State.CONNECTED,
            )
        }
        shadowOf(connectivity).setActiveNetworkInfo(info)
    }

    private fun start(): RunConditionMonitor =
        RunConditionMonitor(
                context,
                context.appDependencies().preferences(),
                { decisions += it },
                {},
            )
            .also { monitor = it }

    private fun broadcast(intent: Intent) {
        context.sendBroadcast(intent)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun localBroadcast(intent: Intent) {
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun job(): JobInfo = jobs.allPendingJobs.single()

    private fun startsSyncthing(job: JobInfo) =
        job.extras.getInt(RunConditionMonitor.EXTRA_BEGIN_ACTIVE_TIME_WINDOW, 0) == 1

    @Test
    fun `a connectivity change is decided again`() {
        network(null)
        start()
        assertEquals(emptyList<Boolean>(), decisions)

        network(ConnectivityManager.TYPE_WIFI)
        broadcast(Intent(ConnectivityManager.CONNECTIVITY_ACTION))

        assertEquals(listOf(true), decisions)
    }

    @Test
    fun `battery saver turning on stops Syncthing`() {
        network(ConnectivityManager.TYPE_WIFI)
        start()

        shadowOf(context.getSystemService(PowerManager::class.java)).setIsPowerSaveMode(true)
        broadcast(Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))

        assertEquals(listOf(true, false), decisions)
    }

    @Test
    fun `unplugging the charger stops Syncthing when it only runs while charging`() {
        network(ConnectivityManager.TYPE_WIFI)
        prefs.edit().putString(Constants.PREF_POWER_SOURCE, Constants.PowerSource.CHARGER).commit()
        context.sendStickyBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_AC)
        )
        start()

        context.sendStickyBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_PLUGGED, 0)
        )
        broadcast(Intent(Intent.ACTION_POWER_DISCONNECTED))

        assertEquals(listOf(true, false), decisions)
    }

    @Test
    fun `turning auto-sync off stops Syncthing when that is respected`() {
        network(ConnectivityManager.TYPE_WIFI)
        prefs.edit().putBoolean(Constants.PREF_RESPECT_MASTER_SYNC, true).commit()
        start()

        ContentResolver.setMasterSyncAutomatically(false)
        ShadowContentResolverWithStatusListeners.notifyListeners()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(true, false), decisions)
    }

    @Test
    fun `the app asks for a new decision after the force buttons change`() {
        network(ConnectivityManager.TYPE_WIFI)
        start()

        prefs
            .edit()
            .putInt(Constants.PREF_BTNSTATE_FORCE_START_STOP, Constants.BTNSTATE_FORCE_STOP)
            .commit()
        localBroadcast(Intent(RunConditionMonitor.ACTION_UPDATE_SHOULDRUN_DECISION))

        assertEquals(listOf(true, false), decisions)
        assertEquals(
            "Syncthing is not running because you forced it to stop regardless of the run conditions.",
            monitor!!.runDecisionExplanation,
        )
    }

    @Test
    fun `on a time schedule Syncthing waits for the sleep interval after its last run`() {
        network(ConnectivityManager.TYPE_WIFI)
        prefs
            .edit()
            .putBoolean(Constants.PREF_RUN_ON_TIME_SCHEDULE, true)
            .putLong(Constants.PREF_LAST_RUN_TIME, SystemClock.elapsedRealtime() - MINUTES_10)
            .commit()

        start()

        assertEquals(emptyList<Boolean>(), decisions)
        assertTrue(
            monitor!!.runDecisionExplanation,
            monitor!!.runDecisionExplanation.contains("10 minutes"),
        )
        val job = job()
        assertEquals((60 - 10) * 60 * 1000L, job.minLatencyMillis)
        assertTrue(startsSyncthing(job))
    }

    @Test
    fun `a time window runs Syncthing for the sync duration, then sleeps`() {
        network(ConnectivityManager.TYPE_WIFI)
        prefs
            .edit()
            .putBoolean(Constants.PREF_RUN_ON_TIME_SCHEDULE, true)
            .putLong(Constants.PREF_LAST_RUN_TIME, SystemClock.elapsedRealtime() - MINUTES_10)
            .commit()
        start()

        localBroadcast(
            Intent(RunConditionMonitor.ACTION_SYNC_TRIGGER_FIRED)
                .putExtra(RunConditionMonitor.EXTRA_BEGIN_ACTIVE_TIME_WINDOW, true)
        )
        assertEquals(listOf(true), decisions)
        assertEquals(5 * 60 * 1000L, jobs.allPendingJobs.maxOf { it.minLatencyMillis })
        assertTrue(jobs.allPendingJobs.none(::startsSyncthing))

        localBroadcast(Intent(RunConditionMonitor.ACTION_SYNC_TRIGGER_FIRED))
        assertEquals(listOf(true, false), decisions)
        val sleep = job()
        assertEquals(60 * 60 * 1000L, sleep.minLatencyMillis)
        assertTrue(startsSyncthing(sleep))
    }

    @Test
    fun `without the time schedule a trigger only checks again later`() {
        network(ConnectivityManager.TYPE_WIFI)
        start()

        localBroadcast(Intent(RunConditionMonitor.ACTION_SYNC_TRIGGER_FIRED))

        assertEquals(listOf(true), decisions)
        val job = job()
        assertEquals(60 * 60 * 1000L, job.minLatencyMillis)
        assertTrue(startsSyncthing(job))
    }

    @Test
    fun `a last run from before a reboot counts as long ago`() {
        network(ConnectivityManager.TYPE_WIFI)
        prefs
            .edit()
            .putLong(Constants.PREF_LAST_RUN_TIME, SystemClock.elapsedRealtime() + MINUTES_10)
            .commit()

        start()

        assertEquals(listOf(true), decisions)
        // The decision then stores the current time as the last run.
        assertEquals(SystemClock.elapsedRealtime(), prefs.getLong(Constants.PREF_LAST_RUN_TIME, 0))
    }

    @Test
    fun `after shutdown jobs are cancelled and broadcasts ignored`() {
        network(null)
        val monitor = start()
        assertTrue(jobs.allPendingJobs.isNotEmpty())

        monitor.shutdown()
        this.monitor = null
        network(ConnectivityManager.TYPE_WIFI)
        broadcast(Intent(ConnectivityManager.CONNECTIVITY_ACTION))
        localBroadcast(Intent(RunConditionMonitor.ACTION_UPDATE_SHOULDRUN_DECISION))

        assertTrue(jobs.allPendingJobs.isEmpty())
        assertEquals(emptyList<Boolean>(), decisions)
        assertEquals(0, ShadowContentResolverWithStatusListeners.registeredListeners)
    }

    private companion object {
        const val MINUTES_10 = 10 * 60 * 1000L
        const val HOURS_3 = 3 * 60 * 60 * 1000L
    }
}
