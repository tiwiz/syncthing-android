package com.nutomic.syncthingandroid.views

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.model.CachedFolderStatus
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.model.FolderStatus
import com.nutomic.syncthingandroid.model.SharedWithDevice
import com.nutomic.syncthingandroid.service.Constants
import java.util.AbstractMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** What a row of the folder list shows for each folder type and Syncthing state. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-rUS")
class FolderRowStateTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun folder(
        type: String = Constants.FOLDER_TYPE_SEND_RECEIVE,
        sharedWith: Int = 2,
        paused: Boolean = false,
    ) =
        Folder().apply {
            id = "photos"
            label = "Photos"
            path = "/storage/emulated/0/DCIM/Camera"
            this.type = type
            this.paused = paused
            repeat(sharedWith) { i ->
                addDevice(SharedWithDevice().apply { deviceID = "device-$i" })
            }
        }

    private fun row(
        folder: Folder = folder(),
        cached: CachedFolderStatus = CachedFolderStatus(),
        status: FolderStatus.() -> Unit = {},
    ) =
        FolderRowState.of(
            context,
            folder,
            AbstractMap.SimpleEntry(FolderStatus().apply(status), cached),
        )

    @Test
    fun `without Syncthing only the label, directory and config problems show`() {
        val folder = folder().apply { invalid = "missing path" }

        val row = FolderRowState.of(context, folder, null)

        assertEquals("Photos", row.label)
        assertEquals("❖ [int]/DCIM/Camera", row.directory)
        assertNull(row.state)
        assertNull(row.items)
        assertNull(row.progress)
        assertFalse(row.showOverride)
        assertFalse(row.showRevert)
        assertEquals("missing path", row.invalid)
    }

    @Test
    fun `a folder without a label shows its id`() {
        val row = FolderRowState.of(context, folder().apply { label = "" }, null)

        assertEquals("photos", row.label)
    }

    @Test
    fun `external and app paths are shortened`() {
        val folder =
            folder().apply {
                path = "/storage/1A2B-3C4D/Android/media/${context.packageName}/Pictures"
            }

        assertEquals(
            "❖ [ext]/Android/media/[app]/Pictures",
            FolderRowState.of(context, folder, null).directory,
        )
    }

    @Test
    fun `an idle folder shared with others is up to date`() {
        val row = row()

        assertEquals("Up to Date", row.state)
        assertEquals(R.color.text_green, row.stateColor)
    }

    @Test
    fun `an idle folder shared with nobody is unshared`() {
        val row = row(folder(sharedWith = 1))

        assertEquals("Unshared", row.state)
        assertEquals(R.color.text_orange, row.stateColor)
    }

    @Test
    fun `items still needed while idle are out of sync, and a send-only folder can override`() {
        val row = row(folder(Constants.FOLDER_TYPE_SEND_ONLY)) { needFiles = 2 }

        assertEquals("Out of Sync", row.state)
        assertEquals(R.color.text_red, row.stateColor)
        assertTrue(row.showOverride)
    }

    @Test
    fun `only send-only folders offer to override`() {
        assertFalse(row { needDeletes = 1 }.showOverride)
    }

    @Test
    fun `failed items come before the state`() {
        val row = row { errors = 3 }

        assertEquals("Failed Items (3)", row.state)
        assertEquals(R.color.text_red, row.stateColor)
    }

    @Test
    fun `a paused folder says so and hides its counts`() {
        val row = row(folder(paused = true)) { state = "scanning" }

        assertEquals("Paused", row.state)
        assertEquals(R.color.text_purple, row.stateColor)
        assertNull(row.items)
    }

    @Test
    fun `a syncing folder shows its progress`() {
        val row =
            row(cached = CachedFolderStatus().apply { completion = 42.7 }) { state = "syncing" }

        assertEquals("Syncing (42%)", row.state)
        assertEquals(R.color.text_blue, row.stateColor)
        assertEquals(42, row.progress)
    }

    @Test
    fun `each other Syncthing state has its text and colour`() {
        val expected =
            mapOf(
                "clean-waiting" to ("Waiting to Clean" to R.color.text_orange),
                "cleaning" to ("Cleaning Versions" to R.color.text_blue),
                "scan-waiting" to ("Waiting to Scan" to R.color.text_orange),
                "scanning" to ("Scanning" to R.color.text_blue),
                "sync-waiting" to ("Waiting to Sync" to R.color.text_orange),
                "sync-preparing" to ("Preparing to Sync" to R.color.text_blue),
                "error" to ("Error" to R.color.text_red),
                "unknown" to ("Unknown" to R.color.text_red),
                "brand-new" to ("brand-new" to R.color.text_red),
            )
        for ((state, textAndColor) in expected) {
            val row = row { this.state = state }
            assertEquals(state, textAndColor.first, row.state)
            assertEquals(state, textAndColor.second, row.stateColor)
            assertNull(state, row.progress)
        }
    }

    @Test
    fun `an error with a message shows it`() {
        assertEquals(
            "Error (disk full)",
            row {
                state = "error"
                error = "disk full"
            }
                .state,
        )
    }

    @Test
    fun `receive-only folders offer to revert local changes`() {
        val row = row(folder(Constants.FOLDER_TYPE_RECEIVE_ONLY)) { receiveOnlyTotalItems = 1 }

        assertTrue(row.showRevert)
        assertEquals(R.string.revert_local_changes, row.revertLabel)
        assertEquals("Local Additions", row.state)
    }

    @Test
    fun `receive-encrypted folders offer to delete unexpected items, not counting deletes`() {
        val folder = folder(Constants.FOLDER_TYPE_RECEIVE_ENCRYPTED)

        assertFalse(
            row(folder) {
                    receiveOnlyTotalItems = 2
                    receiveOnlyChangedDeletes = 2
                }
                .showRevert
        )
        val row =
            row(folder) {
                receiveOnlyTotalItems = 3
                receiveOnlyChangedDeletes = 2
            }
        assertTrue(row.showRevert)
        assertEquals(R.string.delete_unexpected_items, row.revertLabel)
    }

    @Test
    fun `counts and sizes are shown`() {
        val row = row {
            inSyncFiles = 1
            globalFiles = 4
            inSyncBytes = 0
            globalBytes = 0
        }

        assertEquals("∑ 1 / 4 File • 0 B / 0 B", row.items)
    }

    @Test
    fun `conflicts show the count and the first file`() {
        val one =
            row(
                cached =
                    CachedFolderStatus().apply {
                        discoveredConflictFiles = arrayOf("a.sync-conflict.txt")
                    }
            )
        assertEquals("⚠ 1 conflict\n⤮ a.sync-conflict.txt", one.conflicts)

        val two =
            row(cached = CachedFolderStatus().apply { discoveredConflictFiles = arrayOf("a", "b") })
        assertEquals("⚠ 2 conflicts\n⤮ a\n…", two.conflicts)
        assertNull(row().conflicts)
    }

    @Test
    fun `the last synced item shows what happened to it`() {
        fun last(action: String) =
            row(
                cached =
                    CachedFolderStatus().apply {
                        lastItemFinishedAction = action
                        lastItemFinishedItem = "notes.txt"
                        lastItemFinishedTime = "2026-10-15T10:00:00Z"
                    }
            )

        assertEquals("⇌ ⊗ notes.txt", last("delete").lastItemFinished)
        assertEquals("⇌ ⊛ notes.txt", last("update").lastItemFinished)
        assertEquals("⇌ ⁉ notes.txt", last("metadata").lastItemFinished)
        assertTrue(checkNotNull(last("update").lastItemFinishedTime).startsWith("⇌⌚"))
        assertNull(row().lastItemFinished)
        assertNull(row().lastItemFinishedTime)
    }

    @Test
    fun `invalid comes from the status while running`() {
        assertEquals("bad", row { invalid = "bad" }.invalid)
        assertNull(row().invalid)
    }
}
