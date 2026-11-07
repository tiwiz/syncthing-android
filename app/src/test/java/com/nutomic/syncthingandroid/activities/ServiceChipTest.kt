package com.nutomic.syncthingandroid.activities

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.service.SyncthingService.State
import com.nutomic.syncthingandroid.theme.ApplicationTheme
import com.nutomic.syncthingandroid.views.StateTone
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/** The service state in the top bar: shape, icon and words, never a colored dot alone. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-rUS")
class ServiceChipTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun show(state: State) {
        Robolectric.buildActivity(ComponentActivity::class.java).setup().get().setContent {
            ApplicationTheme { ServiceChip(state) }
        }
    }

    private fun description(state: State): String =
        compose
            .onNodeWithTag(serviceChipLook(state).tag)
            .fetchSemanticsNode()
            .config[SemanticsProperties.ContentDescription]
            .joinToString()

    @Test
    fun `every state has a tone and a word`() {
        val looks =
            State.entries.associateWith {
                val look = serviceChipLook(it)
                look.tone to context.getString(look.label)
            }
        assertEquals(
            mapOf(
                State.INIT to (StateTone.OFFLINE to "Sleeping"),
                State.STARTING to (StateTone.BUSY to "Starting"),
                State.ACTIVE to (StateTone.OK to "Running"),
                State.DISABLED to (StateTone.OFFLINE to "Sleeping"),
                State.ERROR to (StateTone.ERROR to "Error"),
            ),
            looks,
        )
    }

    @Test
    fun `the test tags keep the three lights the main screen driver reads`() {
        assertEquals(
            listOf("ACTIVE", "STARTING", "DISABLED", "DISABLED", "DISABLED"),
            listOf(State.ACTIVE, State.STARTING, State.DISABLED, State.INIT, State.ERROR).map {
                serviceChipLook(it).tag.removePrefix("serviceChip:")
            },
        )
    }

    @Test
    fun `on a phone the chip is a badge that screen readers hear as a word`() {
        show(State.ACTIVE)
        assertEquals("Running", description(State.ACTIVE))
        assertEquals(0, compose.onAllNodesWithText("Running").fetchSemanticsNodes().size)
    }

    @Test
    @Config(qualifiers = "en-rUS-w840dp-h800dp")
    fun `from medium width the chip writes the word next to the badge`() {
        show(State.STARTING)
        assertEquals("Starting", description(State.STARTING))
        assertEquals(
            1,
            compose
                .onAllNodesWithText("Starting", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size,
        )
    }
}
