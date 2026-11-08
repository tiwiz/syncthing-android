package com.nutomic.syncthingandroid.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.theme.ApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/** The switch between Home Space and Full Space, and that other devices never see it. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-rUS-w1280dp-h800dp-land")
class SpatialTest {

    @get:Rule val compose = createEmptyComposeRule()

    private fun show(content: @Composable () -> Unit) {
        Robolectric.buildActivity(ComponentActivity::class.java).setup().get().setContent {
            ApplicationTheme { content() }
        }
    }

    @Test
    fun `in Home Space the button offers Full Space`() {
        var toggles = 0
        show { SpaceModeButton(fullSpace = false, onToggle = { toggles++ }) }

        compose.onNodeWithText("Full Space").performClick()

        assertEquals(1, toggles)
    }

    @Test
    fun `in Full Space the button offers Home Space`() {
        var toggles = 0
        show { SpaceModeButton(fullSpace = true, onToggle = { toggles++ }) }

        compose.onNodeWithText("Home Space").performClick()

        assertEquals(1, toggles)
    }

    @Test
    fun `a device without Android XR has no space button`() {
        show { SpaceModeAction() }

        compose.onAllNodesWithText("Full Space").assertCountEquals(0)
        compose.onAllNodesWithText("Home Space").assertCountEquals(0)
    }
}
