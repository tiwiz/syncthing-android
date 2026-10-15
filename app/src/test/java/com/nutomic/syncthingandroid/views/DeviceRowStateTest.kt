package com.nutomic.syncthingandroid.views

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.model.Connection
import com.nutomic.syncthingandroid.model.Device
import com.nutomic.syncthingandroid.model.Folder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** What a row of the device list shows for each connection state. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-rUS")
class DeviceRowStateTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun device(paused: Boolean = false) =
        Device().apply {
            deviceID = "LAPTOP"
            name = "laptop"
            this.paused = paused
        }

    private val photos =
        Folder().apply {
            id = "photos"
            label = "Photos"
        }
    private val music =
        Folder().apply {
            id = "music"
            label = ""
        }

    private fun running(
        connection: Connection = Connection(),
        completion: Int = 100,
        needBytes: Double = 0.0,
        device: Device = device(),
    ) =
        DeviceRowState.of(
            context,
            device,
            lastSeen = "",
            sharedFolders = listOf(photos),
            status = DeviceRowState.Status(connection, completion, needBytes),
        )

    @Test
    fun `a device never seen and sharing nothing`() {
        val row = DeviceRowState.of(context, device(), "1970-01-01T00:00:00Z", emptyList(), null)

        assertEquals("laptop", row.name)
        assertEquals("Last seen: Never", row.lastSeen)
        assertEquals("Unused", row.sharedFoldersTitle)
        assertNull(row.sharedFolders)
    }

    @Test
    fun `shared folders are listed by label, or id without one`() {
        val row = DeviceRowState.of(context, device(), "", listOf(photos, music), null)

        assertEquals("Last seen: Never", row.lastSeen)
        assertEquals("Folders: ", row.sharedFoldersTitle)
        assertEquals("• Photos\n• music", row.sharedFolders)
    }

    @Test
    fun `a device seen before shows when`() {
        val row = DeviceRowState.of(context, device(), "2026-10-15T10:00:00Z", emptyList(), null)

        assertTrue(
            row.lastSeen,
            row.lastSeen.startsWith("Last seen: ") && !row.lastSeen.endsWith("Never"),
        )
    }

    @Test
    fun `a paused device says so, running or not`() {
        for (row in
            listOf(
                DeviceRowState.of(context, device(paused = true), "", emptyList(), null),
                running(Connection().apply { connected = true }, device = device(paused = true)),
            )) {
            assertEquals("Paused", row.status)
            assertEquals(R.color.text_purple, row.statusColor)
            assertNull(row.progress)
            assertNull(row.bandwidth)
        }
    }

    @Test
    fun `without Syncthing a device is disconnected`() {
        val row = DeviceRowState.of(context, device(), "", emptyList(), null)

        assertEquals("Disconnected", row.status)
        assertEquals(R.color.text_red, row.statusColor)
        assertNull(row.progress)
        assertNull(row.bandwidth)
    }

    @Test
    fun `a connected device in sync with little traffic is up to date`() {
        val row =
            running(
                Connection().apply {
                    connected = true
                    inBits = 8 * 1024
                    outBits = 0
                }
            )

        assertEquals("Up to Date", row.status)
        assertEquals(R.color.text_green, row.statusColor)
        assertNull(row.progress)
        assertEquals(
            "⇵ Download ˅ 1 KB/s • Upload ˄ 0 B/s",
            row.bandwidth,
        )
    }

    @Test
    fun `a connected device at 100 percent with traffic is syncing`() {
        val row =
            running(
                Connection().apply {
                    connected = true
                    inBits = 50L * 1024 * 8
                }
            )

        assertEquals("Syncing", row.status)
        assertEquals(R.color.text_blue, row.statusColor)
        assertNull(row.progress)
    }

    @Test
    fun `a connected device below 100 percent shows progress and what is left`() {
        val row =
            running(Connection().apply { connected = true }, completion = 40, needBytes = 2048.0)

        assertEquals("Syncing (40%, 2 KB)", row.status)
        assertEquals(R.color.text_blue, row.statusColor)
        assertEquals(40, row.progress)
    }

    @Test
    fun `a disconnected device tells what it still needs`() {
        assertEquals("Disconnected", running().status)
        val row = running(needBytes = 2048.0)

        assertEquals("Disconnected (2 KB pending)", row.status)
        assertEquals(R.color.text_red, row.statusColor)
        assertNull(row.bandwidth)
    }
}
