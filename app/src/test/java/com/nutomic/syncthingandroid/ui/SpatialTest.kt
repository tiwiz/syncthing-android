package com.nutomic.syncthingandroid.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
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
    @Config(qualifiers = "en-rUS-w400dp-h800dp-port")
    fun `in a narrow window the button is an icon that still has a name`() {
        var toggles = 0
        show { SpaceModeButton(fullSpace = false, onToggle = { toggles++ }) }

        compose.onAllNodesWithText("Full Space").assertCountEquals(0)
        compose.onNodeWithContentDescription("Full Space").performClick()

        assertEquals(1, toggles)
    }

    @Test
    @Config(qualifiers = "en-rUS-w700dp-h800dp-port")
    fun `a medium window also gets only the icon, the top bar has no room for the word`() {
        show { SpaceModeButton(fullSpace = false, onToggle = {}) }

        compose.onAllNodesWithText("Full Space").assertCountEquals(0)
        compose.onNodeWithContentDescription("Full Space").assertExists()
    }

    @Test
    fun `the Full Space dialog shows its words and both buttons`() {
        var confirmed = 0
        var dismissed = 0
        show {
            SpatialDialogBody(
                confirmButton = { TextButton(onClick = { confirmed++ }) { Text("Yes") } },
                dismissButton = { TextButton(onClick = { dismissed++ }) { Text("No") } },
                title = { Text("Restart?") },
                text = { Text("Syncthing restarts.") },
            )
        }

        compose.onNodeWithText("Restart?").assertExists()
        compose.onNodeWithText("Syncthing restarts.").assertExists()
        compose.onNodeWithText("Yes").performClick()
        compose.onNodeWithText("No").performClick()

        assertEquals(1, confirmed)
        assertEquals(1, dismissed)
    }

    @Test
    fun `a scrolling text survives the unbounded height a spatial dialog measures with`() {
        show {
            Box(modifier = Modifier.wrapContentHeight(unbounded = true)) {
                SpatialDialogBody(
                    confirmButton = { TextButton(onClick = {}) { Text("Yes") } },
                    text = {
                        Text(
                            "Report",
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                        )
                    },
                )
            }
        }

        compose.onNodeWithText("Report").assertExists()
        compose.onNodeWithText("Yes").assertExists()
    }

    @Test
    fun `outside Full Space the dialog is the usual one`() {
        var confirmed = 0
        show {
            AppAlertDialog(
                onDismissRequest = {},
                confirmButton = { TextButton(onClick = { confirmed++ }) { Text("Yes") } },
                title = { Text("Restart?") },
            )
        }

        compose.onNodeWithText("Restart?").assertExists()
        compose.onNodeWithText("Yes").performClick()

        assertEquals(1, confirmed)
    }

    @Test
    fun `outside Full Space a screen panel is just its content`() {
        show { SpatialScreenPanel(width = 1024.dp, height = 720.dp) { Text("The screen") } }

        compose.onNodeWithText("The screen").assertExists()
    }

    @Test
    fun `a device without Android XR has no space button`() {
        show { SpaceModeAction() }

        compose.onAllNodesWithText("Full Space").assertCountEquals(0)
        compose.onAllNodesWithText("Home Space").assertCountEquals(0)
    }
}
