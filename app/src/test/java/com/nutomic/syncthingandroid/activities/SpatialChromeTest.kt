package com.nutomic.syncthingandroid.activities

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.theme.ApplicationTheme
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * What Full Space moves into orbiters. An orbiter outside Full Space draws only its content, so the
 * rail and the bar can be tested here; the orbiters themselves are checked on the XR emulator.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-rUS-w1280dp-h800dp-land")
class SpatialChromeTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val calls = mutableListOf<String>()

    /** Records the name of each action called. */
    private inline fun <reified T : Any> recording(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            calls += method.name
            Unit
        } as T

    private fun showRail(tab: MainTab = MainTab.FOLDERS, onTabChange: (MainTab) -> Unit = {}) {
        Robolectric.buildActivity(ComponentActivity::class.java).setup().get().setContent {
            ApplicationTheme {
                SpatialRail(
                    tab = tab,
                    onTabChange = onTabChange,
                    running = true,
                    showWebGui = true,
                    runningAsService = false,
                    menuActions = recording(),
                    actions = recording(),
                )
            }
        }
    }

    private fun showBar(tab: MainTab) {
        Robolectric.buildActivity(ComponentActivity::class.java).setup().get().setContent {
            ApplicationTheme {
                SpatialTopBar(
                    tab = tab,
                    serviceState = SyncthingService.State.ACTIVE,
                    actions = recording(),
                )
            }
        }
    }

    private fun showStatusBar(forceStartStop: Int) {
        Robolectric.buildActivity(ComponentActivity::class.java).setup().get().setContent {
            ApplicationTheme { SpatialStatusBar(forceStartStop, actions = recording()) }
        }
    }

    @Test
    fun `the status bar changes the run mode`() {
        showStatusBar(forceStartStop = 0)

        compose.onNodeWithText("Run").performClick()

        assertEquals(listOf("onForceStartStop"), calls)
    }

    @Test
    fun `the status bar does nothing for the run mode already chosen`() {
        showStatusBar(forceStartStop = 0)

        compose.onNodeWithText("Automatic").performClick()

        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `the status tab leaves the run mode to the orbiter in Full Space`() {
        Robolectric.buildActivity(ComponentActivity::class.java).setup().get().setContent {
            ApplicationTheme {
                StatusTab(status = null, 0, actions = recording(), showRunMode = false)
            }
        }

        compose.onAllNodesWithText("Automatic").assertCountEquals(0)
    }

    @Test
    fun `the status tab has the run mode everywhere else`() {
        Robolectric.buildActivity(ComponentActivity::class.java).setup().get().setContent {
            ApplicationTheme { StatusTab(status = null, 0, actions = recording()) }
        }

        compose.onNodeWithText("Automatic").assertExists()
    }

    @Test
    fun `the rail changes tab`() {
        var chosen: MainTab? = null
        showRail(onTabChange = { chosen = it })

        compose.onNodeWithText("Devices").performClick()

        assertEquals(MainTab.DEVICES, chosen)
    }

    @Test
    fun `the rail lists the overflow entries`() {
        showRail()

        compose.onNodeWithText("Show device ID").performClick()

        assertEquals(listOf("onShowDeviceId"), calls)
    }

    @Test
    fun `the rail has the add button`() {
        showRail()

        compose.onNodeWithContentDescription("Add").performClick()
        compose.onNodeWithText("Add Folder").performClick()

        assertEquals(listOf("onAddFolder"), calls)
    }

    @Test
    fun `the bar names the tab and opens settings`() {
        showBar(MainTab.DEVICES)

        compose.onNodeWithText("Devices").assertExists()
        compose.onNodeWithContentDescription("Running").assertExists()
        compose.onNodeWithContentDescription("Settings").performClick()

        assertEquals(listOf("onSettings"), calls)
    }

    @Test
    fun `only the folders bar rescans`() {
        showBar(MainTab.DEVICES)
        compose.onAllNodesWithContentDescription("Rescan All").assertCountEquals(0)
    }

    @Test
    fun `the folders bar rescans all folders`() {
        showBar(MainTab.FOLDERS)

        compose.onNodeWithContentDescription("Rescan All").performClick()

        assertEquals(listOf("onRescanAll"), calls)
    }
}
