package com.nutomic.syncthingandroid.service

import android.content.Intent
import android.os.Environment
import android.os.Looper
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.testutil.FakeSyncthing
import com.nutomic.syncthingandroid.testutil.FakeSyncthingBinary
import com.nutomic.syncthingandroid.testutil.ShadowContentResolverWithStatusListeners
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import com.nutomic.syncthingandroid.testutil.ShadowUtilDeviceShell
import com.nutomic.syncthingandroid.testutil.registerFreeSpace
import com.nutomic.syncthingandroid.util.ConfigXml
import java.io.File
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

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
class SyncthingServiceLifecycleTest : SyncthingServiceLifecycleHarness() {

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
    fun `a restart does not wait on the main thread for Syncthing to exit`() {
        val (controller, service) = startService()
        val fake = startSyncthingWhenLaunched()
        slowToExit(fake)
        awaitWithClock { service.currentState == SyncthingService.State.ACTIVE }

        val millis = millisTaken {
            controller.withIntent(Intent(SyncthingService.ACTION_RESTART)).startCommand(0, 2)
        }

        assertTrue("took $millis ms", millis < EXIT_MILLIS)
        awaitWithClock { binary.runs.size >= 3 }
        assertEquals(
            listOf("serve --no-browser", "exited", "serve --no-browser"),
            binary.runs.take(3),
        )
        awaitWithClock { service.currentState == SyncthingService.State.ACTIVE }
    }

    @Test
    fun `resetting the deltas does not wait on the main thread for Syncthing to exit`() {
        val (controller, service) = startService()
        val fake = startSyncthingWhenLaunched()
        slowToExit(fake)
        awaitWithClock { service.currentState == SyncthingService.State.ACTIVE }

        val millis = millisTaken {
            controller.withIntent(Intent(SyncthingService.ACTION_RESET_DELTAS)).startCommand(0, 2)
        }

        assertTrue("took $millis ms", millis < EXIT_MILLIS)
        awaitWithClock { binary.runs.size >= 3 }
        assertEquals(
            listOf("serve --no-browser", "exited", "serve --debug-reset-delta-idxs"),
            binary.runs.take(3),
        )
        // It was running before: it keeps running after the reset.
        awaitWithClock { service.currentState == SyncthingService.State.ACTIVE }
    }

    @Test
    fun `resetting the deltas while Syncthing should not run stops it again afterwards`() {
        wifi(connected = false)
        val (controller, service) = startService()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(SyncthingService.State.DISABLED, service.currentState)

        controller.withIntent(Intent(SyncthingService.ACTION_RESET_DELTAS)).startCommand(0, 2)
        startSyncthingWhenLaunched()

        awaitWithClock { "exited" in binary.runs }
        assertEquals(listOf("serve --debug-reset-delta-idxs", "exited"), binary.runs)
        assertEquals(SyncthingService.State.DISABLED, service.currentState)
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
    fun `an export while Syncthing starts waits until it has exited, then starts it again`() {
        val (_, service) = startService()
        awaitWithClock { binary.started }
        assertEquals(SyncthingService.State.STARTING, service.currentState)

        var runsWhenExported: List<String>? = null
        service.exportConfig { exported ->
            assertTrue(exported)
            runsWhenExported = binary.runs
        }
        startSyncthingWhenLaunched()

        awaitWithClock { runsWhenExported != null }
        assertEquals(listOf("serve --no-browser", "exited"), runsWhenExported)
        awaitWithClock { binary.runs.size >= 3 }
        assertEquals("serve --no-browser", binary.runs[2])
    }

    @Test
    fun `an import while Syncthing starts waits until it has exited, then starts it again`() {
        // A backup of this config. Without an HTTPS certificate in it, the import keeps the one
        // the fake serves.
        listOf(
                Constants.getPrivateKeyFile(context),
                Constants.getPublicKeyFile(context),
                Constants.getHttpsKeyFile(context),
            )
            .forEach { it.writeText("fake ${it.name}") }
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val zip = File(Environment.getExternalStorageDirectory(), "backups/syncthing/config.zip")
        assertTrue(exportBackup(context, preferences, zip))
        registerFreeSpace(context)
        val (_, service) = startService()
        awaitWithClock { binary.started }
        assertEquals(SyncthingService.State.STARTING, service.currentState)

        var runsWhenImported: List<String>? = null
        service.importConfig { imported ->
            assertTrue(imported)
            runsWhenImported = binary.runs
        }
        startSyncthingWhenLaunched()

        awaitWithClock { runsWhenImported != null }
        val imported = checkNotNull(runsWhenImported)
        assertEquals(listOf("serve --no-browser", "exited"), imported.take(2))
        // The import may reset the database (its folders are not on this device), never serve.
        assertEquals(1, imported.count { it == "serve --no-browser" })
        awaitWithClock { binary.runs.count { it == "serve --no-browser" } == 2 }
    }

    @Test
    fun `an export deferred while Syncthing starts fails once the service is destroyed`() {
        val (controller, service) = startService()
        awaitWithClock { binary.started }
        assertEquals(SyncthingService.State.STARTING, service.currentState)
        val results = mutableListOf<Boolean>()
        service.exportConfig { results += it }
        shadowOf(Looper.getMainLooper()).idle()

        controller.destroy()
        startSyncthingWhenLaunched()

        awaitWithClock { results.isNotEmpty() && "exited" in binary.runs }
        // Past the deferral of every retry: nothing may start Syncthing again.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3 * DEFER_MILLIS))
        assertEquals(listOf(false), results)
        assertEquals(listOf("serve --no-browser", "exited"), binary.runs)
    }

    @Test
    fun `a stop deferred while Syncthing starts does not crash after the service is destroyed`() {
        val (controller, service) = startService()
        awaitWithClock { binary.started }
        assertEquals(SyncthingService.State.STARTING, service.currentState)
        controller.withIntent(Intent(SyncthingService.ACTION_STOP)).startCommand(0, 2)

        controller.destroy()
        val fake = startSyncthingWhenLaunched()
        slowToExit(fake)

        awaitWithClock { "exited" in binary.runs }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3 * DEFER_MILLIS))
        assertEquals(1, fake.requests("/rest/system/shutdown", "POST").size)
        assertEquals(listOf("serve --no-browser", "exited"), binary.runs)
    }

    @Test
    fun `destroying the service does not wait on the main thread for an export`() {
        val (controller, service) = startService()
        val fake = startSyncthingWhenLaunched()
        slowToExit(fake)
        awaitWithClock { service.currentState == SyncthingService.State.ACTIVE }
        val results = mutableListOf<Boolean>()
        service.exportConfig { results += it }
        shadowOf(Looper.getMainLooper()).idle()

        val millis = millisTaken { controller.destroy() }

        assertTrue("took $millis ms", millis < EXIT_MILLIS)
        awaitWithClock { results.isNotEmpty() }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3 * DEFER_MILLIS))
        assertEquals(listOf(true), results)
        assertEquals(listOf("serve --no-browser", "exited"), binary.runs)
    }

    @Test
    fun `an import after the service is destroyed reports a failure`() {
        val (controller, service) = startService()
        val fake = startSyncthingWhenLaunched()
        awaitWithClock { service.currentState == SyncthingService.State.ACTIVE }
        controller.destroy()
        awaitWithClock { fake.requests("/rest/system/shutdown", "POST").isNotEmpty() }
        val results = mutableListOf<Boolean>()

        service.importConfig { results += it }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(false), results)
    }

    @Test
    fun `evaluating the run conditions after the service is destroyed does nothing`() {
        val (controller, service) = startService()
        val fake = startSyncthingWhenLaunched()
        awaitWithClock { service.currentState == SyncthingService.State.ACTIVE }
        controller.destroy()
        awaitWithClock { "exited" in binary.runs }
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        preferences.edit().remove(Constants.PREF_LAST_RUN_TIME).commit()
        // A changed decision, so that a live monitor would act on it.
        wifi(connected = false)

        // What ImportExportViewModel does once an import that outlived the service is done.
        service.evaluateRunConditions()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3 * DEFER_MILLIS))

        assertTrue(!preferences.contains(Constants.PREF_LAST_RUN_TIME))
        assertEquals(SyncthingService.State.DISABLED, service.currentState)
        assertEquals(listOf("serve --no-browser", "exited"), binary.runs)
        assertEquals(1, fake.requests("/rest/system/shutdown", "POST").size)
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
}
