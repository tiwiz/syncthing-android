package com.nutomic.syncthingandroid.settings

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.JsonParser
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.service.SyncthingConnection
import com.nutomic.syncthingandroid.testutil.ConfigFixture
import com.nutomic.syncthingandroid.testutil.FakeSyncthing
import com.nutomic.syncthingandroid.testutil.RunningSyncthingService
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import com.nutomic.syncthingandroid.testutil.ShadowUtilDeviceShell
import com.nutomic.syncthingandroid.testutil.awaitMain
import java.io.File
import java.nio.file.Files
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import me.zhanghai.compose.preference.MapPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mindrot.jbcrypt.BCrypt
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * Syncthing's own options with Syncthing running, answered by [FakeSyncthing]: what the screen
 * shows, what it writes back, and the maintenance actions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(
    qualifiers = "en-rUS",
    shadows = [ShadowEnvironmentWithAllFilesAccess::class, ShadowUtilDeviceShell::class],
    instrumentedPackages = ["com.nutomic.syncthingandroid.util"],
)
class SyncthingOptionsViewModelTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var syncthing: FakeSyncthing

    @Before
    fun setUp() {
        System.setProperty("os.version", "6.1.0-android")
        ConfigFixture.install(app)
        syncthing = FakeSyncthing(app)
        RunningSyncthingService.install(syncthing)
    }

    @After
    fun tearDown() {
        scope.cancel()
        syncthing.close()
    }

    private fun viewModel(): SyncthingOptionsViewModel {
        val connection = SyncthingConnection(app, scope)
        scope.launch { connection.snapshot.collect {} }
        val viewModel =
            SyncthingOptionsViewModel(app, connection, scope, UnconfinedTestDispatcher())
        awaitMain { viewModel.apiPreferences.value.asMap().isNotEmpty() }
        return viewModel
    }

    private fun idleFor(millis: Long) =
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))

    /** The config the app posted last, as JSON. */
    private fun postedConfig() =
        JsonParser.parseString(
                syncthing.requests("/rest/system/config", "POST").last().body!!.utf8()
            )
            .asJsonObject

    @Test
    fun `the options come from the running config`() {
        val values = viewModel().apiPreferences.value.asMap()

        assertEquals("android-phone", values[SyncthingOptionKeys.DEVICE_NAME])
        assertEquals("fixture-api-key", values[SyncthingOptionKeys.API_KEY])
        assertEquals("default", values[SyncthingOptionKeys.LISTEN_ADDRESSES])
        assertEquals(0, values[SyncthingOptionKeys.INCOMING_RATE_LIMIT])
        assertEquals(true, values[SyncthingOptionKeys.NAT_TRAVERSAL])
        assertEquals(
            syncthing.url.toString().substringAfterLast(':').trim('/').toInt(),
            values[SyncthingOptionKeys.WEB_GUI_PORT],
        )
        assertEquals(false, values[SyncthingOptionKeys.WEB_GUI_REMOTE_ACCESS])
    }

    @Test
    fun `a change is written back half a second after the last one`() {
        val viewModel = viewModel()
        val before = syncthing.requests("/rest/system/config", "POST").size

        val changed = viewModel.apiPreferences.value.asMap().toMutableMap()
        changed[SyncthingOptionKeys.DEVICE_NAME] = "pixel"
        changed[SyncthingOptionKeys.INCOMING_RATE_LIMIT] = 100
        changed[SyncthingOptionKeys.LISTEN_ADDRESSES] = "tcp://:22000, , quic://:22000"
        viewModel.apiPreferences.value = MapPreferences(changed)
        idleFor(400)
        assertEquals(before, syncthing.requests("/rest/system/config", "POST").size)

        idleFor(200)
        awaitMain { syncthing.requests("/rest/system/config", "POST").size > before }
        val config = postedConfig()
        val options = config.getAsJsonObject("options")
        assertEquals(100, options.get("maxRecvKbps").asInt)
        assertEquals(
            listOf("tcp://:22000", "quic://:22000"),
            options.getAsJsonArray("listenAddresses").map { it.asString },
        )
        val local =
            config
                .getAsJsonArray("devices")
                .map { it.asJsonObject }
                .first { it.get("deviceID").asString.startsWith("MFZWI3D") }
        assertEquals("pixel", local.get("name").asString)
    }

    @Test
    fun `changing another option leaves an unanswered usage reporting question open`() {
        val viewModel = viewModel()
        val changed = viewModel.apiPreferences.value.asMap().toMutableMap()
        assertEquals(false, changed[SyncthingOptionKeys.USAGE_REPORTING])
        changed[SyncthingOptionKeys.NAT_TRAVERSAL] = false
        viewModel.apiPreferences.value = MapPreferences(changed)
        idleFor(600)
        awaitMain { syncthing.requests("/rest/system/config", "POST").isNotEmpty() }

        val options = postedConfig().getAsJsonObject("options")
        assertFalse(options.get("natEnabled").asBoolean)
        assertEquals(0, options.get("urAccepted").asInt)
    }

    @Test
    fun `turning usage reporting on accepts the latest version, off declines it`() {
        val viewModel = viewModel()
        val accepted = viewModel.apiPreferences.value.asMap().toMutableMap()
        accepted[SyncthingOptionKeys.USAGE_REPORTING] = true
        viewModel.apiPreferences.value = MapPreferences(accepted)
        idleFor(600)
        awaitMain { syncthing.requests("/rest/system/config", "POST").isNotEmpty() }
        assertEquals(3, postedConfig().getAsJsonObject("options").get("urAccepted").asInt)

        val declined = accepted.toMutableMap()
        declined[SyncthingOptionKeys.USAGE_REPORTING] = false
        viewModel.apiPreferences.value = MapPreferences(declined)
        idleFor(600)
        awaitMain { syncthing.requests("/rest/system/config", "POST").size > 1 }
        assertEquals(-1, postedConfig().getAsJsonObject("options").get("urAccepted").asInt)
    }

    @Test
    fun `remote access binds the web GUI to every address on its port`() {
        val viewModel = viewModel()
        val changed = viewModel.apiPreferences.value.asMap().toMutableMap()
        changed[SyncthingOptionKeys.WEB_GUI_REMOTE_ACCESS] = true
        changed[SyncthingOptionKeys.WEB_GUI_PORT] = 8385
        viewModel.apiPreferences.value = MapPreferences(changed)
        idleFor(600)
        awaitMain { syncthing.requests("/rest/system/config", "POST").isNotEmpty() }

        assertEquals(
            "0.0.0.0:8385",
            postedConfig().getAsJsonObject("gui").get("address").asString,
        )
    }

    @Test
    fun `the web GUI password is sent hashed`() {
        val viewModel = viewModel()
        val changed = viewModel.apiPreferences.value.asMap().toMutableMap()
        changed[SyncthingOptionKeys.WEB_GUI_PASSWORD] = "secret"
        viewModel.apiPreferences.value = MapPreferences(changed)
        idleFor(600)
        awaitMain { syncthing.requests("/rest/system/config", "POST").isNotEmpty() }

        val hash = postedConfig().getAsJsonObject("gui").get("password").asString
        assertFalse(hash.contains("secret"))
        assertTrue(BCrypt.checkpw("secret", hash))
    }

    @Test
    fun `clearing versions empties every folder's versions directory`() {
        val folder = Files.createTempDirectory("camera").toFile()
        val versions = File(folder, ".stversions/old").apply { mkdirs() }
        File(versions, "photo~20261018.jpg").writeText("old")
        syncthing.respond(
            "GET",
            "/rest/system/config",
            syncthing.configJson().replace("/storage/emulated/0/DCIM/Camera", folder.path),
        )
        RunningSyncthingService.install(syncthing)

        viewModel().clearVersions()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(File(folder, ".stversions").exists())
        assertEquals(0, File(folder, ".stversions").listFiles()!!.size)
        assertEquals(
            app.getString(R.string.clear_stversions_done),
            ShadowToast.getTextOfLatestToast(),
        )
    }

    @Test
    fun `undoing ignored devices and folders sends the config and says so`() {
        val viewModel = viewModel()
        val before = syncthing.requests("/rest/system/config", "POST").size

        viewModel.undoIgnoredDevicesAndFolders()
        awaitMain { syncthing.requests("/rest/system/config", "POST").size > before }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            app.getString(R.string.undo_ignored_devices_folders_done),
            ShadowToast.getTextOfLatestToast(),
        )
    }

    @Test
    fun `the support bundle is saved to Downloads under the device name`() {
        syncthing.respond("GET", "/rest/debug/support", "PK-bundle")
        val viewModel = viewModel()

        viewModel.downloadSupportBundle()
        awaitMain { viewModel.supportBundle.value.state == SupportBundleDownloadState.SUCCESS }

        val file = File(viewModel.supportBundle.value.fileName)
        assertEquals("syncthing-support-bundle_android-phone.zip", file.name)
        assertEquals("PK-bundle", file.readText())
    }

    @Test
    fun `a support bundle Syncthing fails to produce ends as failed`() {
        syncthing.respond("GET", "/rest/debug/support", "", code = 500)
        val viewModel = viewModel()

        viewModel.downloadSupportBundle()
        awaitMain { viewModel.supportBundle.value.state == SupportBundleDownloadState.FAILED }

        assertFalse(File(viewModel.supportBundle.value.fileName).exists())
    }
}
