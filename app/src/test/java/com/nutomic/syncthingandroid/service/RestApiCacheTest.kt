package com.nutomic.syncthingandroid.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.model.FolderStatus
import com.nutomic.syncthingandroid.model.Options
import com.nutomic.syncthingandroid.testutil.FakeSyncthing
import com.nutomic.syncthingandroid.testutil.ShadowContentResolverWithStatusListeners
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import com.nutomic.syncthingandroid.testutil.ShadowUtilDeviceShell
import com.nutomic.syncthingandroid.testutil.StoppedSyncthingService
import com.nutomic.syncthingandroid.testutil.awaitMain
import com.nutomic.syncthingandroid.testutil.drainMain
import java.io.File
import java.net.URL
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * More of [RestApi]: the folder and device caches it keeps from events, overall sync completion,
 * usage reporting, settings edits and the ignore lists.
 */
@RunWith(AndroidJUnit4::class)
@Config(
    qualifiers = "en-rUS",
    shadows =
        [
            ShadowEnvironmentWithAllFilesAccess::class,
            ShadowUtilDeviceShell::class,
            ShadowContentResolverWithStatusListeners::class,
        ],
    instrumentedPackages = ["com.nutomic.syncthingandroid.util"],
)
class RestApiCacheTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var syncthing: FakeSyncthing
    private lateinit var api: RestApi
    private var configChanged = 0

    @Before
    fun setUp() {
        val service = StoppedSyncthingService.install()
        syncthing = FakeSyncthing(context)
        var available = false
        api = RestApi(service, syncthing.url, API_KEY, { available = true }, { configChanged++ })
        api.readConfigFromRestApi()
        awaitMain { available }
        awaitMain { syncthing.requests("/rest/stats/device").isNotEmpty() }
        drainMain()
    }

    @After
    fun tearDown() {
        syncthing.close()
    }

    private fun posts() = syncthing.requests("/rest/system/config", "POST").size

    /** The config posted by [action], once Syncthing received it. */
    private fun postedConfig(action: () -> Unit): String {
        val before = posts()
        action()
        awaitMain { posts() > before }
        return checkNotNull(syncthing.requests("/rest/system/config", "POST").last().body).utf8()
    }

    @Test
    fun `undoing ignores clears ignored devices and folders`() {
        api.ignoreDevice(NEW_ID, "tablet", "192.0.2.9")
        api.ignoreFolder(LAPTOP_ID, "music", "Music")
        awaitMain { posts() == 2 }

        api.undoIgnoredDevicesAndFolders()
        val posted = postedConfig { api.sendConfig() }
        assertTrue(posted, posted.contains("\"remoteIgnoredDevices\":[]"))
        assertFalse(posted, posted.contains("\"music\""))
    }

    @Test
    fun `usage reporting is accepted for the version Syncthing reports`() {
        assertFalse(api.isUsageReportingAccepted)
        assertFalse(api.isUsageReportingDecided)

        api.setUsageReporting(true)
        assertTrue(api.isUsageReportingAccepted)
        assertTrue(api.isUsageReportingDecided)
        assertEquals(3, api.options.urAccepted)

        api.setUsageReporting(false)
        assertFalse(api.isUsageReportingAccepted)
        assertTrue(api.isUsageReportingDecided)
        assertEquals(Options.USAGE_REPORTING_DENIED, api.options.urAccepted)
    }

    @Test
    fun `settings edits apply to the cached config and gui updates are sent`() {
        val gui = api.gui.apply { theme = "dark" }
        val options = api.options.apply { natEnabled = false }

        api.editSettings(gui, options)
        assertEquals("dark", api.gui.theme)
        assertFalse(api.options.natEnabled)
        assertEquals(0, syncthing.requests("/rest/system/config", "POST").size)

        val posted = postedConfig { api.updateGui(api.gui.apply { theme = "black" }) }
        assertTrue(posted.contains("\"theme\":\"black\""))
        assertEquals(1, configChanged)
    }

    @Test
    fun `the copies handed out do not change the cache`() {
        api.gui.theme = "changed"
        api.folders.single().label = "changed"
        api.getDevices(true)[0].name = "changed"

        assertEquals("default", api.gui.theme)
        assertEquals("Camera", api.folders.single().label)
        assertTrue(api.getDevices(true).none { it.name == "changed" })
    }

    @Test
    fun `the GUI URL falls back to the default address`() {
        assertEquals(URL(syncthing.url.toString()), api.webGuiUrl)

        api.updateGui(api.gui.apply { address = null })

        assertEquals(URL("https://127.0.0.1:8384"), api.webGuiUrl)
    }

    @Test
    fun `without the local device in the config there is no local device`() {
        api.removeDevice(LOCAL_ID)

        assertThrows(RuntimeException::class.java) { api.localDevice }
    }

    @Test
    fun `folder pause and state events update the folder cache`() {
        api.updateLocalFolderPause("cam-photos", true)
        api.updateLocalFolderState("cam-photos", "syncing")

        val status = api.getFolderStatus("cam-photos")
        assertTrue(status.value.paused)
        assertEquals("syncing", status.key.state)
    }

    @Test
    fun `a folder status not yet in the cache is queried`() {
        val before = syncthing.requests("/rest/db/status").size

        api.getFolderStatus("cam-photos")
        awaitMain { syncthing.requests("/rest/db/status").size > before }
        drainMain()

        assertEquals(
            "cam-photos",
            syncthing.requests("/rest/db/status").last().url.queryParameter("folder"),
        )
        assertTrue(api.getFolderStatus("cam-photos").key.stateChanged.isNotEmpty())
    }

    @Test
    fun `remote device connection and pause events update the device cache`() {
        api.updateRemoteDeviceConnected(LAPTOP_ID, true)
        assertTrue(api.getRemoteDeviceStatus(LAPTOP_ID).connected)

        api.updateRemoteDevicePaused(LAPTOP_ID, true)
        val status = api.getRemoteDeviceStatus(LAPTOP_ID)
        assertFalse(status.connected)
        assertTrue(status.paused)
    }

    @Test
    fun `overall completion combines connected devices and local folders`() {
        assertEquals(-1, api.totalSyncCompletion)

        api.updateRemoteDeviceConnected(LAPTOP_ID, true)
        assertEquals(100, api.totalSyncCompletion)

        api.setRemoteCompletionInfo(LAPTOP_ID, "cam-photos", 100.0, 40.0)
        assertEquals(40, api.totalSyncCompletion)
        assertEquals(40, api.getRemoteDeviceCompletion(LAPTOP_ID))
        assertEquals(100.0, api.getRemoteDeviceNeedBytes(LAPTOP_ID), 0.0)

        api.setLocalFolderStatus(
            "cam-photos",
            FolderStatus().apply {
                globalBytes = 100
                inSyncBytes = 50
                state = "syncing"
            },
        )
        assertEquals(45, api.totalSyncCompletion)
    }

    @Test
    fun `a paused folder counts as complete on remote devices`() {
        api.updateLocalFolderPause("cam-photos", true)
        val posted = postedConfig { api.updateFolder(api.folders.single().apply { paused = true }) }
        syncthing.respond("GET", "/rest/system/config", posted)
        api.reloadConfig()
        awaitMain { api.folders.single().paused }
        api.updateRemoteDeviceConnected(LAPTOP_ID, true)

        api.setRemoteCompletionInfo(LAPTOP_ID, "cam-photos", 500.0, 10.0)

        assertEquals(100, api.getRemoteDeviceCompletion(LAPTOP_ID))
        assertEquals(0.0, api.getRemoteDeviceNeedBytes(LAPTOP_ID), 0.0)
    }

    @Test
    fun `a finished update is recorded as a delete when the file is gone`() {
        val dir = Files.createTempDirectory("camera").toFile()
        val config = syncthing.configJson().replace("/storage/emulated/0/DCIM/Camera", dir.path)
        syncthing.respond("GET", "/rest/system/config", config)
        api.reloadConfig()
        awaitMain { api.folders.single().path == dir.path }
        File(dir, "here.jpg").writeText("x")

        api.setLocalFolderLastItemFinished("cam-photos", "update", "here.jpg", "t1")
        assertEquals("update", api.getFolderStatus("cam-photos").value.lastItemFinishedAction)

        api.setLocalFolderLastItemFinished("cam-photos", "update", "gone.jpg", "t2")
        assertEquals("delete", api.getFolderStatus("cam-photos").value.lastItemFinishedAction)
        assertEquals("t2", api.getFolderStatus("cam-photos").value.lastItemFinishedTime)
    }

    @Test
    fun `folders under the home folder get the full path`() {
        val config =
            syncthing.configJson().replace("\"/storage/emulated/0/DCIM/Camera\"", "\"~/Camera\"")
        syncthing.respond("GET", "/rest/system/config", config)
        api.reloadConfig()

        awaitMain { api.folders.single().path?.endsWith("/syncthing/Camera") == true }
    }

    @Test
    fun `the ignore list is posted for its folder`() {
        api.postFolderIgnoreList("cam-photos", arrayOf("*.tmp", "!keep"))
        awaitMain { syncthing.requests("/rest/db/ignores", "POST").isNotEmpty() }

        val request = syncthing.requests("/rest/db/ignores", "POST").single()
        assertEquals("cam-photos", request.url.queryParameter("folder"))
        assertEquals("{\"ignore\":[\"*.tmp\",\"!keep\"]}", checkNotNull(request.body).utf8())
    }

    @Test
    fun `total connection statistics come from the last connections answer`() {
        api.getRemoteDeviceStatus("")
        awaitMain { api.totalConnectionStatistic.at.isNotEmpty() }

        assertEquals("2026-10-13T00:01:47+02:00", api.totalConnectionStatistic.at)
    }

    private companion object {
        const val API_KEY = "fixture-api-key"
        const val LOCAL_ID = "MFZWI3D-BONSGYC-YLTMRWG-C43ENR5-QXGZDMM-FZWI3DP-BONSGYY-LTMRWAD"
        const val LAPTOP_ID = "P56IOI7-MZJNU2Y-IQGDREY-DM2MGTI-MGL3BXN-PQ6W5BM-TBBZ4TJ-XZWICQ2"
        const val NEW_ID = "AIR6LPZ-7K4PTTV-UXQSMUU-CPQ5YWH-OEDFIIQ-JUG777G-2YQXXR5-YD6AWQR"
    }
}
