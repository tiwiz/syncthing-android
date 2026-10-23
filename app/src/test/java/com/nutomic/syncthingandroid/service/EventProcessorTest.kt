package com.nutomic.syncthingandroid.service

import android.app.NotificationManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.nutomic.syncthingandroid.appDependencies
import com.nutomic.syncthingandroid.model.Event
import com.nutomic.syncthingandroid.testutil.FakeSyncthing
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import com.nutomic.syncthingandroid.testutil.ShadowUtilDeviceShell
import com.nutomic.syncthingandroid.testutil.StoppedSyncthingService
import com.nutomic.syncthingandroid.testutil.awaitMain
import com.nutomic.syncthingandroid.testutil.drainMain
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaScannerConnection

/**
 * Characterizes [EventProcessor]: how it polls `/rest/events` through [RestApi] against
 * [FakeSyncthing], and what each event type changes in the [RestApi] caches or the notifications.
 */
@RunWith(AndroidJUnit4::class)
@Config(
    qualifiers = "en-rUS",
    shadows = [ShadowEnvironmentWithAllFilesAccess::class, ShadowUtilDeviceShell::class],
    instrumentedPackages = ["com.nutomic.syncthingandroid.util"],
)
class EventProcessorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private lateinit var service: SyncthingService
    private lateinit var syncthing: FakeSyncthing
    private lateinit var api: RestApi
    private lateinit var processor: EventProcessor

    @Before
    fun setUp() {
        service = StoppedSyncthingService.install()
        syncthing = FakeSyncthing(context)
        var available = false
        api =
            RestApi(
                service,
                service.appDependencies().notificationHandler(),
                syncthing.url,
                "fixture-api-key",
                { available = true },
                {},
            )
        api.readConfigFromRestApi()
        awaitMain { available }
        // Let the startup follow-ups (pending devices, completion, ...) finish first.
        awaitMain { syncthing.requests("/rest/stats/device").isNotEmpty() }
        drainMain()
        processor =
            EventProcessor(
                service,
                api,
                service.appDependencies().preferences(),
                service.appDependencies().notificationHandler(),
            )
    }

    @After
    fun tearDown() {
        processor.stop()
        syncthing.close()
    }

    private fun eventRequests() =
        syncthing.requests("/rest/events").map {
            "since=${it.url.queryParameter("since")} limit=${it.url.queryParameter("limit")}"
        }

    private fun send(json: String) {
        val element = JsonParser.parseString(json)
        processor.onEvent(Gson().fromJson(element, Event::class.java), element)
    }

    /** Serves the camera folder at [path] with [type] and reloads the config, as after a change. */
    private fun useCameraFolder(path: String, type: String = Constants.FOLDER_TYPE_SEND_ONLY) {
        val config =
            syncthing
                .configJson()
                .replace("/storage/emulated/0/DCIM/Camera", path)
                .replace("\"type\": \"sendonly\"", "\"type\": \"$type\"")
        syncthing.respond("GET", "/rest/system/config", config)
        api.reloadConfig()
        awaitMain { api.folders.single().path == path }
        drainMain()
    }

    private fun notificationTexts(): List<String> =
        shadowOf(notifications).allNotifications.map {
            it.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString()
        }

    @Test
    fun `a poll checks the newest event, then reads from the stored id and stores the last one`() {
        prefs.edit().putLong(Constants.PREF_EVENT_PROCESSOR_LAST_SYNC_ID, 5).apply()

        processor.run()
        awaitMain { prefs.getLong(Constants.PREF_EVENT_PROCESSOR_LAST_SYNC_ID, 0) == 16L }

        assertEquals(listOf("since=0 limit=1", "since=5 limit=0"), eventRequests())
    }

    @Test
    fun `when event ids went backwards Syncthing restarted, so reading starts from zero`() {
        prefs.edit().putLong(Constants.PREF_EVENT_PROCESSOR_LAST_SYNC_ID, 100).apply()

        processor.run()
        awaitMain { syncthing.requests("/rest/events").size == 2 }
        drainMain()

        assertEquals(listOf("since=0 limit=1", "since=0 limit=0"), eventRequests())
        assertEquals(16L, prefs.getLong(Constants.PREF_EVENT_PROCESSOR_LAST_SYNC_ID, 0))
    }

    @Test
    fun `start polls every five seconds until stopped`() {
        val looper = shadowOf(Looper.getMainLooper())

        processor.start()
        looper.idleFor(Duration.ofSeconds(4))
        drainMain()
        assertTrue(eventRequests().isEmpty())

        looper.idleFor(Duration.ofSeconds(1))
        awaitMain { eventRequests().size == 2 }
        drainMain()
        looper.idleFor(Duration.ofSeconds(5))
        awaitMain { eventRequests().size == 4 }
        drainMain()

        processor.stop()
        looper.idleFor(Duration.ofSeconds(30))
        drainMain()
        assertEquals(4, eventRequests().size)
    }

    @Test
    fun `a failed poll is retried after five seconds`() {
        syncthing.respond("GET", "/rest/events", "boom", code = 500)
        val looper = shadowOf(Looper.getMainLooper())

        processor.start()
        looper.idleFor(Duration.ofSeconds(5))
        awaitMain { eventRequests().size == 1 }
        drainMain()
        looper.idleFor(Duration.ofSeconds(5))
        awaitMain(describe = { eventRequests().toString() }) { eventRequests().size == 2 }

        assertEquals(listOf("since=0 limit=1", "since=0 limit=1"), eventRequests())
    }

    @Test
    fun `the captured events update the folder caches and reload the config`() {
        val configReadsBefore = syncthing.requests("/rest/system/config", "GET").size

        processor.run()
        awaitMain { prefs.getLong(Constants.PREF_EVENT_PROCESSOR_LAST_SYNC_ID, 0) == 16L }
        drainMain()

        // Two ConfigSaved events, two reloads.
        assertEquals(2, syncthing.requests("/rest/system/config", "GET").size - configReadsBefore)
        val (status, cached) = api.getFolderStatus("cam-photos").let { it.key to it.value }
        assertEquals("idle", status.state)
        assertEquals(1L, status.localFiles)
        // LocalIndexUpdated reports "update"; RestApi records an update of a file that is not on
        // disk (as here, in the test) as a deletion.
        assertEquals("delete", cached.lastItemFinishedAction)
        assertEquals("a.txt", cached.lastItemFinishedItem)
        // A remote folder at 0% counts as up to date, like in the web GUI.
        assertEquals(100, api.getRemoteDeviceCompletion(LAPTOP_ID))
    }

    @Test
    fun `device connection events update the device cache`() {
        send("""{"id": 1, "type": "DeviceConnected", "data": {"id": "$LAPTOP_ID"}}""")
        assertTrue(api.getRemoteDeviceStatus(LAPTOP_ID).connected)

        send("""{"id": 2, "type": "DeviceDisconnected", "data": {"id": "$LAPTOP_ID"}}""")
        assertFalse(api.getRemoteDeviceStatus(LAPTOP_ID).connected)

        send("""{"id": 3, "type": "DevicePaused", "data": {"device": "$LAPTOP_ID"}}""")
        assertTrue(api.getRemoteDeviceStatus(LAPTOP_ID).paused)

        send("""{"id": 4, "type": "DeviceResumed", "data": {"device": "$LAPTOP_ID"}}""")
        assertFalse(api.getRemoteDeviceStatus(LAPTOP_ID).paused)
    }

    @Test
    fun `folder state, pause and completion events update the folder cache`() {
        send(
            """{"id": 1, "type": "StateChanged", "data": {"folder": "cam-photos", "to": "syncing"}}"""
        )
        assertEquals("syncing", api.getFolderStatus("cam-photos").key.state)

        send("""{"id": 2, "type": "FolderPaused", "data": {"id": "cam-photos"}}""")
        assertTrue(api.getFolderStatus("cam-photos").value.paused)

        send("""{"id": 3, "type": "FolderResumed", "data": {"id": "cam-photos"}}""")
        assertFalse(api.getFolderStatus("cam-photos").value.paused)

        send(
            """{"id": 4, "type": "FolderCompletion", "data": {"device": "$LAPTOP_ID",
               "folder": "cam-photos", "completion": 40, "needBytes": 6}}"""
        )
        assertEquals(40, api.getRemoteDeviceCompletion(LAPTOP_ID))
        assertEquals(6.0, api.getRemoteDeviceNeedBytes(LAPTOP_ID), 0.0)
    }

    @Test
    fun `remote index updates are remembered per folder`() {
        send(
            """{"id": 1, "type": "RemoteIndexUpdated",
               "data": {"device": "$LAPTOP_ID", "folder": "cam-photos", "items": 0}}"""
        )
        assertFalse(api.getFolderStatus("cam-photos").value.remoteIndexUpdated)

        send(
            """{"id": 2, "type": "RemoteIndexUpdated",
               "data": {"device": "$LAPTOP_ID", "folder": "cam-photos", "items": 3}}"""
        )
        assertTrue(api.getFolderStatus("cam-photos").value.remoteIndexUpdated)
    }

    @Test
    fun `ignored event types do not count as remote index updates`() {
        send(
            """{"id": 1, "type": "RemoteDownloadProgress",
               "data": {"device": "$LAPTOP_ID", "folder": "cam-photos", "items": 3}}"""
        )

        assertFalse(api.getFolderStatus("cam-photos").value.remoteIndexUpdated)
    }

    @Test
    fun `a finished item is rescanned by the media scanner`() {
        send(
            """{"id": 1, "type": "ItemFinished", "time": "2026-10-13T10:00:00Z",
               "data": {"action": "update", "folder": "cam-photos", "item": "IMG_1.jpg"}}"""
        )

        assertEquals(
            setOf("/storage/emulated/0/DCIM/Camera/IMG_1.jpg"),
            ShadowMediaScannerConnection.getSavedPaths(),
        )
        assertEquals("IMG_1.jpg", api.getFolderStatus("cam-photos").value.lastItemFinishedItem)
    }

    @Test
    fun `a finished item in an unknown folder is skipped`() {
        send(
            """{"id": 1, "type": "ItemFinished",
               "data": {"action": "update", "folder": "missing", "item": "IMG_1.jpg"}}"""
        )

        assertTrue(ShadowMediaScannerConnection.getSavedPaths().isEmpty())
    }

    @Test
    fun `metadata changes and other actions are not rescanned`() {
        send(
            """{"id": 1, "type": "ItemFinished",
               "data": {"action": "metadata", "folder": "cam-photos", "item": "IMG_1.jpg"}}"""
        )
        send(
            """{"id": 2, "type": "ItemFinished",
               "data": {"action": "rename", "folder": "cam-photos", "item": "IMG_2.jpg"}}"""
        )

        assertTrue(ShadowMediaScannerConnection.getSavedPaths().isEmpty())
        assertEquals("IMG_2.jpg", api.getFolderStatus("cam-photos").value.lastItemFinishedItem)
    }

    @Test
    fun `a deleted file leaves the media store unless it still exists`() {
        val media = Robolectric.setupContentProvider(RecordingMediaProvider::class.java, "media")
        val dir = java.nio.file.Files.createTempDirectory("camera").toFile()
        useCameraFolder(dir.path)
        java.io.File(dir, "still-here.jpg").writeText("x")

        send(
            """{"id": 1, "type": "ItemFinished",
               "data": {"action": "delete", "folder": "cam-photos", "item": "still-here.jpg"}}"""
        )
        send(
            """{"id": 2, "type": "ItemFinished",
               "data": {"action": "delete", "folder": "cam-photos", "item": "gone.jpg"}}"""
        )
        drainMain()

        assertEquals(listOf(listOf("${dir.path}/gone.jpg")), media.deletes)
    }

    @Test
    fun `items in receive-encrypted folders are not shown to the media scanner`() {
        useCameraFolder("/storage/emulated/0/DCIM/Camera", Constants.FOLDER_TYPE_RECEIVE_ENCRYPTED)

        send(
            """{"id": 1, "type": "ItemFinished",
               "data": {"action": "update", "folder": "cam-photos", "item": "blob"}}"""
        )

        assertTrue(ShadowMediaScannerConnection.getSavedPaths().isEmpty())
    }

    @Test
    fun `the newest local change becomes the folder's last item`() {
        val dir = java.nio.file.Files.createTempDirectory("camera").toFile()
        useCameraFolder(dir.path)
        java.io.File(dir, "b.jpg").writeText("x")

        send(
            """{"id": 1, "type": "LocalIndexUpdated", "time": "2026-10-13T11:00:00Z",
               "data": {"folder": "cam-photos", "filenames": ["a.jpg", "b.jpg"]}}"""
        )

        val status = api.getFolderStatus("cam-photos").value
        assertEquals("b.jpg", status.lastItemFinishedItem)
        assertEquals("update", status.lastItemFinishedAction)
        assertEquals("2026-10-13T11:00:00Z", status.lastItemFinishedTime)
    }

    @Test
    fun `a local change keeps the file name as Syncthing sent it`() {
        val dir = java.nio.file.Files.createTempDirectory("camera").toFile()
        useCameraFolder(dir.path)
        java.io.File(dir, "Tom's \"best\".jpg").writeText("x")

        send(
            """{"id": 1, "type": "LocalIndexUpdated", "time": "2026-10-13T11:00:00Z",
               "data": {"folder": "cam-photos", "filenames": ["Tom's \"best\".jpg"]}}"""
        )

        assertEquals(
            "Tom's \"best\".jpg",
            api.getFolderStatus("cam-photos").value.lastItemFinishedItem,
        )
    }

    @Test
    fun `running out of disk space shows a notification`() {
        fun crashTitle() =
            shadowOf(notifications)
                .allNotifications
                .single()
                .extras
                .getString(NotificationCompat.EXTRA_TITLE)

        send(
            """{"id": 1, "type": "ItemFinished",
               "data": {"action": "update", "folder": "cam-photos", "item": "sub/IMG_1.jpg",
                        "error": "write: no space left on device"}}"""
        )
        assertEquals("The SDcard is full. File sub/IMG_1.jpg", crashTitle())
        assertTrue(ShadowMediaScannerConnection.getSavedPaths().isEmpty())

        send(
            """{"id": 2, "type": "FolderErrors", "data": {"folder": "cam-photos", "errors": [
               {"error": "insufficient space in basic /storage", "path": "sub/IMG_2.jpg"}]}}"""
        )
        // Both share one notification id, so the second replaces the first.
        assertEquals("The SDcard is full. File sub/IMG_2.jpg", crashTitle())
    }

    @Test
    fun `pending device and folder events show consent notifications`() {
        send(
            """{"id": 1, "type": "PendingDevicesChanged", "data": {"added": [
               {"deviceID": "$NEW_ID", "name": "tablet", "address": "192.0.2.9:22000"}]}}"""
        )
        send(
            """{"id": 2, "type": "PendingFoldersChanged", "data": {"added": [
               {"deviceID": "$LAPTOP_ID", "folderID": "music", "folderLabel": "Music",
                "receiveEncrypted": false, "remoteEncrypted": false}]}}"""
        )

        assertEquals(
            listOf(
                "Device \"laptop\" wants to share folder \"Music (music)\"",
                "Device \"tablet\" wants to connect",
            ),
            notificationTexts().sorted(),
        )
    }

    @Test
    fun `a saved config is reloaded`() {
        val before = syncthing.requests("/rest/system/config", "GET").size

        send("""{"id": 1, "type": "ConfigSaved", "data": {}}""")
        awaitMain { syncthing.requests("/rest/system/config", "GET").size > before }
    }

    /** Stands in for MediaStore, which Robolectric does not let the app delete from. */
    class RecordingMediaProvider : ContentProvider() {
        val deletes = mutableListOf<List<String>>()

        override fun onCreate() = true

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            deletes += selectionArgs.orEmpty().toList()
            return 1
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0
    }

    private companion object {
        const val LAPTOP_ID = "P56IOI7-MZJNU2Y-IQGDREY-DM2MGTI-MGL3BXN-PQ6W5BM-TBBZ4TJ-XZWICQ2"
        const val NEW_ID = "AIR6LPZ-7K4PTTV-UXQSMUU-CPQ5YWH-OEDFIIQ-JUG777G-2YQXXR5-YD6AWQR"
    }
}
