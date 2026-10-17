package com.nutomic.syncthingandroid.activities

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.service.SyncthingService.State
import com.nutomic.syncthingandroid.theme.ApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/** The status tab's hero: a loading indicator while Syncthing starts, else the state badge. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-rUS")
class StatusHeroTest {

    @get:Rule val compose = createEmptyComposeRule()

    private fun show(content: @Composable () -> Unit) {
        Robolectric.buildActivity(ComponentActivity::class.java)
            .setup()
            .get()
            .setContent(content = content)
    }

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun shown(state: State): List<String> {
        show { ApplicationTheme { StatusHero(statusInfo(context, state, "", null)) } }
        return listOf("statusLoading", "statusBadge").filter {
            compose.onAllNodesWithTag(it, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `a starting Syncthing shows a loading indicator`() {
        assertEquals(listOf("statusLoading"), shown(State.STARTING))
    }

    @Test
    fun `otherwise the hero shows the state badge`() {
        assertEquals(listOf("statusBadge"), shown(State.ACTIVE))
    }
}
