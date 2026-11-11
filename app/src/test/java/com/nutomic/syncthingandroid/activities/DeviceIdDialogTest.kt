package com.nutomic.syncthingandroid.activities

import android.app.Application
import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.theme.ApplicationTheme
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
class DeviceIdDialogTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun show(
        isCurrentDevice: Boolean = false,
        fontScale: Float = 1f,
        onDismiss: () -> Unit = {},
    ) {
        Robolectric.buildActivity(AppCompatActivity::class.java).setup().get().apply {
            setTheme(R.style.Theme_Syncthing)
            setContent {
                ApplicationTheme {
                    val density = LocalDensity.current
                    CompositionLocalProvider(
                        LocalDensity provides Density(density.density, fontScale)
                    ) {
                        DeviceIdDialog(DeviceIdRequest(NAME, ID, isCurrentDevice), onDismiss)
                    }
                }
            }
        }
        compose.waitForIdle()
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
        var dismissed = false
        show(onDismiss = { dismissed = true })
        compose.onNodeWithContentDescription("Close device id").performClick()

        assertEquals(true, dismissed)
    }

    @Test
    fun `with very large text the buttons can still be scrolled to`() {
        show(fontScale = 3f)

        button("Share").performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "en-rUS-land")
    fun `with very large text in landscape the buttons can still be scrolled to`() {
        show(fontScale = 3f)

        button("Share").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the request survives in the saved state until it is dismissed`() {
        val saved = SavedStateHandle()
        val request = DeviceIdRequest(NAME, ID, isCurrentDevice = true)
        DeviceIdDialogState(saved).show(request)

        val restored = DeviceIdDialogState(saved)
        assertEquals(request, restored.request.value)

        restored.dismiss()
        assertNull(restored.request.value)
        assertNull(DeviceIdDialogState(saved).request.value)
    }

    private companion object {
        const val NAME = "laptop"
        const val ID = "P56IOI7-MZJNU2Y-IQGDREY-DM2MGTI-MGL3BXN-PQ6W5BM-TBBZ4TJ-XZWICQ2"
    }
}
