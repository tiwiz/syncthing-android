package com.nutomic.syncthingandroid.activities

import android.content.Context
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nutomic.syncthingandroid.testutil.ConfigFixture
import com.nutomic.syncthingandroid.testutil.ShadowContentResolverWithStatusListeners
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import com.nutomic.syncthingandroid.testutil.ShadowUtilDeviceShell
import com.nutomic.syncthingandroid.testutil.StoppedSyncthingService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The main screen on a large window: folders and devices open in a detail pane next to their list
 * instead of a screen of their own. Driven through [MainScreen].
 */
@RunWith(AndroidJUnit4::class)
@Config(
    qualifiers = "en-rUS-w900dp-h900dp-port",
    shadows =
        [
            ShadowEnvironmentWithAllFilesAccess::class,
            ShadowContentResolverWithStatusListeners::class,
            ShadowUtilDeviceShell::class,
        ],
    instrumentedPackages = ["com.nutomic.syncthingandroid.util"],
)
class MainActivityListDetailTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        System.setProperty("os.version", "6.1.0-android")
        LocalBroadcastManager::class.java.getDeclaredField("mInstance").apply {
            isAccessible = true
            set(null, null)
        }
        ConfigFixture.install(context)
    }

    private fun stopped(): MainScreen {
        StoppedSyncthingService.install()
        return MainScreen(ActivityScenario.launch(MainActivity::class.java), compose).also {
            it.idle()
        }
    }

    @Test
    fun `with nothing open the detail pane asks for a choice and the list keeps its width`() {
        stopped().use { screen ->
            screen.showTab(MainScreen.Tab.FOLDERS)
            assertEquals("Choose a folder", screen.detailPlaceholder())
            assertTrue(screen.rowWidth("folder:Camera") < screen.rootSize().width / 2f)

            screen.showTab(MainScreen.Tab.DEVICES)
            assertEquals("Choose a device", screen.detailPlaceholder())
            assertTrue(screen.rowWidth("device:laptop") < screen.rootSize().width / 2f)

            screen.open(MainScreen.Tab.DEVICES, "laptop")
            assertNull(screen.detailPlaceholder())
        }
    }

    @Test
    @Config(qualifiers = "en-rUS-w1280dp-h800dp-land")
    fun `the status tab stays a readable column on a large window`() {
        stopped().use { screen ->
            screen.showTab(MainScreen.Tab.STATUS)
            assertTrue(screen.statusWidthDp() <= MAX_STATUS_WIDTH_DP)
        }
    }

    @Test
    fun `a pane next to its list closes with a close button, not a back arrow`() {
        stopped().use { screen ->
            screen.open(MainScreen.Tab.FOLDERS, "Camera")
            assertEquals(listOf("Close"), screen.detailNavigation())

            screen.closeDetail()

            assertNull(screen.detailTitle())
        }
    }

    @Test
    fun `a folder opens in the detail pane, not a screen of its own`() {
        stopped().use { screen ->
            screen.showTab(MainScreen.Tab.FOLDERS)
            assertNull(screen.open(MainScreen.Tab.FOLDERS, "Camera"))

            assertEquals("Edit Folder", screen.detailTitle())
            assertEquals(listOf("Camera"), screen.folders())
        }
    }

    @Test
    fun `a device opens in the detail pane, not a screen of its own`() {
        stopped().use { screen ->
            screen.showTab(MainScreen.Tab.DEVICES)
            assertNull(screen.open(MainScreen.Tab.DEVICES, "laptop"))

            assertEquals("Edit Device", screen.detailTitle())
            assertEquals(listOf("laptop"), screen.devices())
        }
    }

    @Test
    fun `opening a pane does not focus a field, so the keyboard stays down`() {
        // As on a phone or tablet: only text fields take focus, which brings up the keyboard.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.setInTouchMode(true)
        try {
            stopped().use { screen ->
                screen.showTab(MainScreen.Tab.FOLDERS)
                screen.open(MainScreen.Tab.FOLDERS, "Camera")
                assertFalse(screen.anyFieldFocused())

                screen.showTab(MainScreen.Tab.DEVICES)
                screen.open(MainScreen.Tab.DEVICES, "laptop")
                assertFalse(screen.anyFieldFocused())
            }
        } finally {
            instrumentation.setInTouchMode(false)
        }
    }

    @Test
    fun `the row open in the pane is marked as selected`() {
        stopped().use { screen ->
            screen.showTab(MainScreen.Tab.FOLDERS)
            assertFalse(screen.folderSelected("Camera"))

            screen.open(MainScreen.Tab.FOLDERS, "Camera")
            assertTrue(screen.folderSelected("Camera"))

            screen.back()
            assertFalse(screen.folderSelected("Camera"))

            screen.showTab(MainScreen.Tab.DEVICES)
            screen.open(MainScreen.Tab.DEVICES, "laptop")
            assertTrue(screen.deviceSelected("laptop"))
        }
    }

    @Test
    fun `the pane shrinks with the back gesture and stays when it is cancelled`() {
        stopped().use { screen ->
            screen.showTab(MainScreen.Tab.FOLDERS)
            screen.open(MainScreen.Tab.FOLDERS, "Camera")
            val width = checkNotNull(screen.detailPaneWidth())

            screen.startBackGesture(progress = 1f)
            assertTrue(checkNotNull(screen.detailPaneWidth()) < width)

            screen.cancelBackGesture()
            assertEquals("Edit Folder", screen.detailTitle())
            assertEquals(width, checkNotNull(screen.detailPaneWidth()), 0.5f)
        }
    }

    @Test
    fun `back closes the detail pane first, then keeps the screen in the background`() {
        stopped().use { screen ->
            screen.showTab(MainScreen.Tab.FOLDERS)
            screen.open(MainScreen.Tab.FOLDERS, "Camera")

            screen.back()
            assertNull(screen.detailTitle())
            assertFalse(screen.finishing())

            screen.back()
            assertFalse(screen.finishing())
        }
    }

    @Test
    fun `a row is not marked as selected while the window is too narrow for its pane`() {
        stopped().use { screen ->
            screen.showTab(MainScreen.Tab.FOLDERS)
            screen.open(MainScreen.Tab.FOLDERS, "Camera")

            // A foldable folded, or a tablet split: the pane is kept but not shown.
            RuntimeEnvironment.setQualifiers("w411dp-h891dp-port")
            screen.recreate()
            assertNull(screen.detailTitle())
            assertFalse(screen.folderSelected("Camera"))

            RuntimeEnvironment.setQualifiers("w900dp-h900dp-port")
            screen.recreate()
            assertEquals("Edit Folder", screen.detailTitle())
            assertTrue(screen.folderSelected("Camera"))
        }
    }

    @Test
    fun `the pane and its unsaved changes survive recreation`() {
        stopped().use { screen ->
            screen.showTab(MainScreen.Tab.FOLDERS)
            screen.open(MainScreen.Tab.FOLDERS, "Camera")
            screen.setDetailFolderLabel("Photos")

            screen.recreate()

            assertEquals("Edit Folder", screen.detailTitle())
            assertEquals("Photos", screen.detailFolderLabel())
        }
    }

    @Test
    fun `the pane keeps its folder and ViewModel across recreation`() {
        stopped().use { screen ->
            screen.showTab(MainScreen.Tab.FOLDERS)
            screen.open(MainScreen.Tab.FOLDERS, "Camera")
            val viewModel = checkNotNull(screen.detailViewModel())

            screen.recreate()

            assertEquals("Edit Folder", screen.detailTitle())
            assertTrue(screen.folderSelected("Camera"))
            assertSame(viewModel, screen.detailViewModel())
        }
    }

    @Test
    fun `opening a row over unsaved changes asks first, then opens it afresh`() {
        stopped().use { screen ->
            screen.showTab(MainScreen.Tab.FOLDERS)
            screen.open(MainScreen.Tab.FOLDERS, "Camera")
            val label = screen.detailFolderLabel()
            screen.setDetailFolderLabel("Photos")

            screen.open(MainScreen.Tab.FOLDERS, "Camera")
            assertTrue(screen.discardAsked())
            assertEquals("Photos", screen.detailFolderLabel())

            screen.confirmDiscard()
            assertEquals("Edit Folder", screen.detailTitle())
            assertEquals(label, screen.detailFolderLabel())
        }
    }

    @Test
    fun `unsaved changes in the pane ask before it closes`() {
        stopped().use { screen ->
            screen.showTab(MainScreen.Tab.FOLDERS)
            screen.open(MainScreen.Tab.FOLDERS, "Camera")
            screen.setDetailFolderLabel("Photos")

            screen.back()
            assertEquals("Edit Folder", screen.detailTitle())
            assertTrue(screen.discardAsked())

            screen.confirmDiscard()
            assertNull(screen.detailTitle())
        }
    }

    private companion object {
        const val MAX_STATUS_WIDTH_DP = 720f
    }
}
