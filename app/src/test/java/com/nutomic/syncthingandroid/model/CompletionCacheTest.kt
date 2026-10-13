package com.nutomic.syncthingandroid.model

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The local and remote completion caches RestApi fills from events and REST answers. */
@RunWith(AndroidJUnit4::class)
class CompletionCacheTest {

    private companion object {
        const val D1 = "AAAAAAA-DEVICE1"
        const val D2 = "BBBBBBB-DEVICE2"
        const val GONE = "CCCCCCC-REMOVED"
    }

    private fun folder(id: String, vararg sharedWith: String) =
        Folder().apply {
            this.id = id
            sharedWith.forEach { deviceId -> addDevice(Device().apply { deviceID = deviceId }) }
        }

    private fun device(id: String) = Device().apply { deviceID = id }

    private fun status(inSync: Long, global: Long, state: String = "syncing") =
        FolderStatus().apply {
            inSyncBytes = inSync
            globalBytes = global
            this.state = state
        }

    private fun connection(connected: Boolean) = Connection().apply { this.connected = connected }

    private fun info(completion: Double, needBytes: Double = 0.0) =
        RemoteCompletionInfo().apply {
            this.completion = completion
            this.needBytes = needBytes
        }

    // LocalCompletion

    @Test
    fun `local completion is 100 without folders`() {
        assertEquals(100, LocalCompletion(false).totalFolderCompletion)
    }

    @Test
    fun `an unknown folder has default status`() {
        val entry = LocalCompletion(false).getFolderStatus("nope")

        assertEquals("idle", entry.key.state)
        assertEquals(100.0, entry.value.completion, 0.0)
    }

    @Test
    fun `folder completion is the in-sync share of the global bytes, rounded down`() {
        val cache = LocalCompletion(false)

        cache.setFolderStatus("a", false, status(inSync = 2, global = 3))

        assertEquals(66.0, cache.getFolderStatus("a").value.completion, 0.0)
    }

    @Test
    fun `an idle folder, an empty one or one with more than global is complete`() {
        val cache = LocalCompletion(false)

        cache.setFolderStatus("idle", false, status(inSync = 1, global = 4, state = "idle"))
        cache.setFolderStatus("empty", false, status(inSync = 0, global = 0))
        cache.setFolderStatus("over", false, status(inSync = 5, global = 4))

        listOf("idle", "empty", "over").forEach {
            assertEquals(it, 100.0, cache.getFolderStatus(it).value.completion, 0.0)
        }
    }

    @Test
    fun `total completion averages the incomplete folders that are not paused`() {
        val cache = LocalCompletion(false)
        cache.setFolderStatus("a", false, status(inSync = 1, global = 4))
        cache.setFolderStatus("b", false, status(inSync = 3, global = 4))
        cache.setFolderStatus("paused", true, status(inSync = 0, global = 4))
        cache.setFolderStatus("done", false, status(inSync = 4, global = 4))

        assertEquals(50, cache.totalFolderCompletion)
    }

    @Test
    fun `the short overload keeps the paused flag`() {
        val cache = LocalCompletion(false)
        cache.setFolderStatus("a", true, status(inSync = 1, global = 4))

        cache.setFolderStatus("a", status(inSync = 2, global = 4))

        assertTrue(cache.getFolderStatus("a").value.paused)
        assertEquals(50.0, cache.getFolderStatus("a").value.completion, 0.0)
    }

    @Test
    fun `the status handed out is a copy`() {
        val cache = LocalCompletion(false)
        cache.setFolderStatus("a", false, status(inSync = 1, global = 4))

        cache.getFolderStatus("a").value.completion = 99.0
        cache.getFolderStatus("a").key.state = "changed"

        assertEquals(25.0, cache.getFolderStatus("a").value.completion, 0.0)
        assertEquals("syncing", cache.getFolderStatus("a").key.state)
    }

    @Test
    fun `config updates add new folders and drop removed ones`() {
        val cache = LocalCompletion(false)
        cache.setFolderStatus("gone", false, status(inSync = 1, global = 4))

        cache.updateFromConfig(listOf(folder("new")))

        assertEquals(100, cache.totalFolderCompletion)
        assertEquals("idle", cache.getFolderStatus("new").key.state)
    }

    @Test
    fun `item finished, remote index and conflicts are stored per folder`() {
        val cache = LocalCompletion(false)
        cache.setFolderStatus("a", false, status(inSync = 1, global = 4))

        cache.setLastItemFinished("a", "update", "doc.txt", "2026-10-13T10:00:00Z")
        cache.setRemoteIndexUpdated("a", true)
        cache.setDiscoveredConflictFiles("a", arrayOf("x.sync-conflict"))

        val cached = cache.getFolderStatus("a").value
        assertEquals("update", cached.lastItemFinishedAction)
        assertEquals("doc.txt", cached.lastItemFinishedItem)
        assertEquals("2026-10-13T10:00:00Z", cached.lastItemFinishedTime)
        assertTrue(cached.remoteIndexUpdated)
        assertArrayEquals(arrayOf("x.sync-conflict"), cached.discoveredConflictFiles)
        assertEquals(25.0, cached.completion, 0.0)
    }

    // RemoteCompletion

    @Test
    fun `remote completion is not applicable without connected devices`() {
        val cache = RemoteCompletion(false)
        cache.setDeviceStatus(D1, connection(connected = false))

        assertEquals(-1, cache.totalDeviceCompletion)
        assertEquals(0, cache.onlineDeviceCount)
    }

    @Test
    fun `remote completion averages the folders in progress on connected devices`() {
        val cache = RemoteCompletion(false)
        cache.setDeviceStatus(D1, connection(connected = true))
        cache.setDeviceStatus(D2, connection(connected = false))
        cache.setCompletionInfo(D1, "a", info(20.0))
        cache.setCompletionInfo(D1, "b", info(60.0))
        // 0% and 100% count as up to date, like in Syncthing's web UI.
        cache.setCompletionInfo(D1, "c", info(0.0))
        cache.setCompletionInfo(D1, "d", info(100.0))
        cache.setCompletionInfo(D2, "a", info(10.0))

        assertEquals(40, cache.totalDeviceCompletion)
        assertEquals(1, cache.onlineDeviceCount)
    }

    @Test
    fun `device completion and needed bytes add up its folders`() {
        val cache = RemoteCompletion(false)
        cache.setCompletionInfo(D1, "a", info(30.0, needBytes = 100.0))
        cache.setCompletionInfo(D1, "b", info(150.0, needBytes = 20.0))

        assertEquals(30, cache.getDeviceCompletion(D1))
        assertEquals(120.0, cache.getDeviceNeedBytes(D1), 0.0)
        assertEquals(100, cache.getDeviceCompletion("UNKNOWN-DEVICE"))
        assertEquals(0.0, cache.getDeviceNeedBytes("UNKNOWN-DEVICE"), 0.0)
    }

    @Test
    fun `device status is stored as a copy and keeps the folders`() {
        val cache = RemoteCompletion(false)
        cache.setCompletionInfo(D1, "a", info(30.0))
        val connection = connection(connected = true).apply { address = "tcp://1.2.3.4" }

        cache.setDeviceStatus(D1, connection)
        connection.address = "changed"
        cache.getDeviceStatus(D1).address = "changed too"

        assertEquals("tcp://1.2.3.4", cache.getDeviceStatus(D1).address)
        assertEquals(30, cache.getDeviceCompletion(D1))
        assertFalse(cache.getDeviceStatus("UNKNOWN-DEVICE").connected)
    }

    @Test
    fun `config updates follow devices and the folders shared with them`() {
        val cache = RemoteCompletion(false)
        cache.setCompletionInfo(GONE, "a", info(30.0))
        cache.setCompletionInfo(D1, "removed", info(30.0))

        cache.updateFromConfig(
            listOf(device(D1), device(D2)),
            listOf(folder("shared", D1), folder("private")),
        )
        cache.setDeviceStatus(D1, connection(connected = true))
        cache.setDeviceStatus(D2, connection(connected = true))

        // "shared" starts at 100% on D1, "removed" and the device GONE are dropped.
        assertEquals(100, cache.getDeviceCompletion(D1))
        assertEquals(100, cache.getDeviceCompletion(GONE))
        assertEquals(100, cache.totalDeviceCompletion)
        assertEquals(2, cache.onlineDeviceCount)
    }

    @Test
    fun `device ids are shortened for logs`() {
        val cache = RemoteCompletion(false)

        assertEquals("ABCDEFG", cache.getShortenedDeviceId("ABCDEFG-HIJKLMN"))
        assertEquals("", cache.getShortenedDeviceId(""))
    }
}
