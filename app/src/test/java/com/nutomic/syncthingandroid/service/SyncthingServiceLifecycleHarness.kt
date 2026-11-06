package com.nutomic.syncthingandroid.service

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.nutomic.syncthingandroid.testutil.ConfigFixture
import com.nutomic.syncthingandroid.testutil.FakeSyncthing
import com.nutomic.syncthingandroid.testutil.FakeSyncthingBinary
import com.nutomic.syncthingandroid.testutil.ShadowUtilDeviceShell
import java.net.ServerSocket
import java.time.Duration
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Before
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowNetworkInfo

/**
 * What the [SyncthingService] lifecycle tests share: a real service started the way the system
 * does, with [FakeSyncthingBinary] as the native binary and [FakeSyncthing] answering on the GUI
 * port once the binary runs.
 */
open class SyncthingServiceLifecycleHarness {

    protected val context: Context = ApplicationProvider.getApplicationContext()
    protected lateinit var binary: FakeSyncthingBinary
    protected var syncthing: FakeSyncthing? = null
    protected val port = ServerSocket(0).use { it.localPort }

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
        ShadowUtilDeviceShell.throwOnKillProcess = false
    }

    /**
     * Like [awaitMain], but lets the main looper's clock run, so the service's delayed retries
     * (polling the GUI every 150 ms) happen.
     */
    protected fun awaitWithClock(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting on the service" }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(STEP_MILLIS))
            Thread.sleep(STEP_MILLIS)
        }
    }

    /** Waits for the binary, then brings up the fake API on the GUI port, as Syncthing would. */
    protected fun startSyncthingWhenLaunched(): FakeSyncthing {
        awaitWithClock { binary.started }
        return FakeSyncthing(context, port, resetVolley = false).also {
            it.onShutdown = binary::stop
            syncthing = it
        }
    }

    protected fun startService(): Pair<ServiceController<SyncthingService>, SyncthingService> {
        binary.install()
        // The service starts polling the GUI right away; FakeSyncthing must not reset the queue.
        FakeSyncthing.resetVolleyQueue()
        val controller = Robolectric.buildService(SyncthingService::class.java, Intent()).create()
        controller.startCommand(0, 1)
        return controller to controller.get()
    }

    /** Syncthing that takes [EXIT_MILLIS] to exit after the REST shutdown. */
    protected fun slowToExit(fake: FakeSyncthing) {
        fake.onShutdown = {
            thread {
                Thread.sleep(EXIT_MILLIS)
                binary.stop()
            }
        }
    }

    protected fun millisTaken(action: () -> Unit): Long {
        val begin = System.nanoTime()
        action()
        return (System.nanoTime() - begin) / NANOS_PER_MILLI
    }

    protected fun wifi(connected: Boolean) {
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

    /**
     * Waits off the main looper, so nothing queued there runs meanwhile, until [condition] holds
     * for what the lifecycle job wrote.
     */
    protected fun awaitBackground(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting off the main looper" }
            Thread.sleep(STEP_MILLIS)
        }
    }

    protected fun certResults():
        MutableList<Pair<SyncthingService.HttpsCertReplaceResult, String?>> = mutableListOf()

    protected companion object {
        const val TIMEOUT_MILLIS = 10_000L
        const val STEP_MILLIS = 20L
        const val EXIT_MILLIS = 1500L
        const val DEFER_MILLIS = 1000L
        const val HTTPS_CERT_VERIFY_TIMEOUT_MS = 30_000L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
