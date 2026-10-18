package com.nutomic.syncthingandroid.fragments

import android.app.Application
import android.content.ClipboardManager
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The device ID dialog: copying and sharing the ID, and how it names the device. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-rUS")
class DeviceIdDialogFragmentTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun show(isCurrentDevice: Boolean = false): AppCompatActivity {
        val activity =
            Robolectric.buildActivity(AppCompatActivity::class.java).setup().get().apply {
                setTheme(R.style.Theme_Syncthing)
            }
        DeviceIdDialogFragment.show(activity.supportFragmentManager, NAME, ID, isCurrentDevice)
        compose.waitForIdle()
        return activity
    }

    private fun button(text: String) = compose.onNode(hasText(text) and hasClickAction())

    @Test
    fun `copy puts the ID on the clipboard`() {
        show()
        button("Copy").performClick()

        val clipboard = app.getSystemService(ClipboardManager::class.java)
        assertEquals(ID, clipboard.primaryClip?.getItemAt(0)?.text)
    }

    @Test
    fun `share offers the ID to other apps`() {
        show()
        button("Share").performClick()

        val chooser = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = checkNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals(ID, send.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun `only this device is marked as such`() {
        show(isCurrentDevice = true)
        compose.onNodeWithText("This device").assertExists()
    }

    @Test
    fun `a remote device is not marked as this device`() {
        show(isCurrentDevice = false)
        compose.onNodeWithText(NAME).assertExists()
        compose.onNodeWithText("This device").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "en-rUS-land")
    fun `in landscape the dialog closes with its own button`() {
        val activity = show()
        compose.onNodeWithContentDescription("Close device id").performClick()
        compose.waitForIdle()

        assertNull(activity.supportFragmentManager.findFragmentByTag("DeviceIdDialog"))
    }

    private companion object {
        const val NAME = "laptop"
        const val ID = "P56IOI7-MZJNU2Y-IQGDREY-DM2MGTI-MGL3BXN-PQ6W5BM-TBBZ4TJ-XZWICQ2"
    }
}
