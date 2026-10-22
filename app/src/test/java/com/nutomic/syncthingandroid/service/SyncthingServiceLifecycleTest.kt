package com.nutomic.syncthingandroid.service

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.os.Looper
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.testutil.ConfigFixture
import com.nutomic.syncthingandroid.testutil.FakeSyncthing
import com.nutomic.syncthingandroid.testutil.FakeSyncthingBinary
import com.nutomic.syncthingandroid.testutil.ShadowContentResolverWithStatusListeners
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import com.nutomic.syncthingandroid.testutil.ShadowUtilDeviceShell
import com.nutomic.syncthingandroid.util.ConfigXml
import java.net.ServerSocket
import java.time.Duration
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkInfo

/**
 * Starts and stops a real [SyncthingService] the way the system does, with [FakeSyncthingBinary] as
 * the native binary and [FakeSyncthing] answering on the GUI port once the binary runs: DISABLED →
 * STARTING → ACTIVE, then back to DISABLED through a REST shutdown.
 */
@RunWith(AndroidJUnit4::class)
@Config(
    qualifiers = "en-rUS",
    shadows =
        [
            ShadowEnvironmentWithAllFilesAccess::class,
            ShadowContentResolverWithStatusListeners::class,
            ShadowUtilDeviceShell::class,
        ],
    instrumentedPackages = ["com.nutomic.syncthingandroid.util"],
)
class SyncthingServiceLifecycleTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var binary: FakeSyncthingBinary
    private var syncthing: FakeSyncthing? = null
    private val port = ServerSocket(0).use { it.localPort }

    @Before
    fun setUp() {
        System.setProperty("os.version", "6.1.0-android")
        ConfigFixture.install(context) {
            it.replace("<address>127.0.0.1:8384</address>", "<address>127.0.0.1:$port</address>")
        }
        // Default run conditions: run on Wi-Fi.
        shadowOf(context.getSystemService(ConnectivityManager::class.java))
            .setActiveNetworkInfo(
                ShadowNetworkInfo.newInstance(
                    NetworkInfo.DetailedState.CONNECTED,
                    ConnectivityManager.TYPE_WIFI,
                    0,
                    true,
                    NetworkInfo.State.CONNECTED,
                )
            )
        binary = FakeSyncthingBinary(context).apply { serveUntilStopped = true }
    }

    @After
    fun tearDown() {
        binary.stop()
        syncthing?.close()
        ShadowUtilDeviceShell.netstatOutput = ""
    }

    /**
     * Like [awaitMain], but lets the main looper's clock run, so the service's delayed retries
     * (polling the GUI every 150 ms) happen.
     */
    private fun awaitWithClock(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting on the service" }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(STEP_MILLIS))
            Thread.sleep(STEP_MILLIS)
        }
    }

    /** Waits for the binary, then brings up the fake API on the GUI port, as Syncthing would. */
    private fun startSyncthingWhenLaunched(): FakeSyncthing {
        awaitWithClock { binary.started }
        return FakeSyncthing(context, port, resetVolley = false).also {
            it.onShutdown = binary::stop
            syncthing = it
        }
    }

    private fun startService(): Pair<ServiceController<SyncthingService>, SyncthingService> {
        binary.install()
        // The service starts polling the GUI right away; FakeSyncthing must not reset the queue.
        FakeSyncthing.resetVolleyQueue()
        val controller = Robolectric.buildService(SyncthingService::class.java, Intent()).create()
        controller.startCommand(0, 1)
        return controller to controller.get()
    }

    @Test
    fun `starting runs the binary and becomes active once the API answers`() {
        val (_, service) = startService()

        val fake = startSyncthingWhenLaunched()
        awaitWithClock { service.currentState == SyncthingService.State.ACTIVE }

        assertEquals(listOf("serve", "--no-browser"), binary.args)
        assertEquals(context.filesDir.path, binary.env["STHOMEDIR"])
        assertNotNull(service.api)
        assertTrue(checkNotNull(service.api).isConfigLoaded)
        assertTrue(fake.requests("/rest/system/version").isNotEmpty())
    }

    @Test
    fun `destroying the service shuts Syncthing down through the API`() {
        val (controller, service) = startService()
        val fake = startSyncthingWhenLaunched()
        awaitWithClock { service.currentState == SyncthingService.State.ACTIVE }

        controller.destroy()

        assertEquals(SyncthingService.State.DISABLED, service.currentState)
        assertEquals(1, fake.requests("/rest/system/shutdown", "POST").size)
        assertNull(service.api)
    }

    @Test
    fun `a GUI port already in use keeps the binary from starting`() {
        ShadowUtilDeviceShell.netstatOutput =
            "Proto Recv-Q Send-Q Local Address Foreign Address State\n" +
                "tcp 0 0 127.0.0.1:$port 0.0.0.0:* LISTEN\n"

        val (_, service) = startService()

        assertEquals(SyncthingService.State.DISABLED, service.currentState)
        assertTrue(!binary.started)
    }

    @Test
    fun `a stop intent shuts an active Syncthing down`() {
        val (controller, service) = startService()
        val fake = startSyncthingWhenLaunched()
        awaitWithClock { service.currentState == SyncthingService.State.ACTIVE }

        controller.withIntent(Intent(SyncthingService.ACTION_STOP)).startCommand(0, 2)

        assertEquals(SyncthingService.State.DISABLED, service.currentState)
        // Syncthing is asked to exit off the main thread.
        awaitWithClock { fake.requests("/rest/system/shutdown", "POST").isNotEmpty() }
        assertEquals(1, fake.requests("/rest/system/shutdown", "POST").size)
    }

    /** Syncthing that takes [EXIT_MILLIS] to exit after the REST shutdown. */
    private fun slowToExit(fake: FakeSyncthing) {
        fake.onShutdown = {
            thread {
                Thread.sleep(EXIT_MILLIS)
                binary.stop()
            }
        }
    }

    private fun millisTaken(action: () -> Unit): Long {
        val begin = System.nanoTime()
        action()
        return (System.nanoTime() - begin) / NANOS_PER_MILLI
    }

    private fun wifi(connected: Boolean) {
        val info =
            ShadowNetworkInfo.newInstance(
                    NetworkInfo.DetailedState.CONNECTED,
                    ConnectivityManager.TYPE_WIFI,
                    0,
                    true,
                    NetworkInfo.State.CONNECTED,
                )
                .takeIf { connected }
        shadowOf(context.getSystemService(ConnectivityManager::class.java))
            .setActiveNetworkInfo(info)
        context.sendBroadcast(Intent(ConnectivityManager.CONNECTIVITY_ACTION))
    }

    @Test
    fun `a stop intent does not wait on the main thread for Syncthing to exit`() {
        val (controller, service) = startService()
        val fake = startSyncthingWhenLaunched()
        slowToExit(fake)
        awaitWithClock { service.currentState == SyncthingService.State.ACTIVE }

        val millis = millisTaken {
            controller.withIntent(Intent(SyncthingService.ACTION_STOP)).startCommand(0, 2)
        }

        assertTrue("took $millis ms", millis < EXIT_MILLIS)
        assertEquals(SyncthingService.State.DISABLED, service.currentState)
        assertNull(service.api)
        awaitWithClock { "exited" in binary.runs }
        assertEquals(1, fake.requests("/rest/system/shutdown", "POST").size)
    }

    @Test
    fun `a start while Syncthing is still exiting waits until it has exited`() {
        val (_, service) = startService()
        val fake = startSyncthingWhenLaunched()
        slowToExit(fake)
        awaitWithClock { service.currentState == SyncthingService.State.ACTIVE }

        val millis = millisTaken {
            wifi(connected = false)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertTrue("took $millis ms", millis < EXIT_MILLIS)
        assertEquals(SyncthingService.State.DISABLED, service.currentState)
        wifi(connected = true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("serve --no-browser"), binary.runs)

        awaitWithClock { binary.runs.size >= 3 }
        assertEquals(
            listOf("serve --no-browser", "exited", "serve --no-browser"),
            binary.runs.take(3),
        )
    }

    @Test
    fun `resetting the database waits off the main thread, then starts Syncthing again`() {
        val (controller, service) = startService()
        val fake = startSyncthingWhenLaunched()
        slowToExit(fake)
        awaitWithClock { service.currentState == SyncthingService.State.ACTIVE }

        val millis = millisTaken {
            controller.withIntent(Intent(SyncthingService.ACTION_RESET_DATABASE)).startCommand(0, 2)
        }

        assertTrue("took $millis ms", millis < EXIT_MILLIS)
        awaitWithClock { binary.runs.size >= 4 }
        assertEquals(
            listOf("serve --no-browser", "exited", "debug reset-database", "exited"),
            binary.runs.take(4),
        )
        awaitWithClock { binary.runs.size >= 5 }
        assertEquals("serve --no-browser", binary.runs[4])
    }

    @Test
    fun `a folder whose own run conditions fail is paused in the config before the start`() {
        val prefix = Constants.PREF_OBJECT_PREFIX_FOLDER + "cam-photos"
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putBoolean(Constants.dynPrefObjectCustomSyncConditions(prefix), true)
            .putBoolean(Constants.dynPrefObjectSyncOnWifi(prefix), false)
            .commit()

        startService()
        awaitWithClock { binary.started }

        val config = ConfigXml(context).apply { loadConfig() }
        assertTrue(config.folders.single().paused)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
        const val STEP_MILLIS = 20L
        const val EXIT_MILLIS = 1500L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
