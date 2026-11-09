package com.nutomic.syncthingandroid.recentchanges

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/** The recent changes screen on its own, fed a list and a loading flag. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-rUS")
class RecentChangesScreenTest {

    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun `the empty message waits for the first load to end`() {
        var refreshing by mutableStateOf(true)
        Robolectric.buildActivity(ComponentActivity::class.java).setup().get().setContent {
            RecentChangesScreen(
                changes = emptyList(),
                isRefreshing = refreshing,
                showExactTimes = false,
                onNavigateBack = {},
                onRefresh = {},
                onToggleExactTimes = {},
                onItemClick = {},
            )
        }
        compose.onNodeWithText(EMPTY).assertDoesNotExist()

        refreshing = false
        compose.onNodeWithText(EMPTY).assertExists()
    }

    private fun show(pullToRefresh: Boolean, onRefresh: () -> Unit) {
        Robolectric.buildActivity(ComponentActivity::class.java).setup().get().setContent {
            RecentChangesScreen(
                changes =
                    List(40) {
                        RecentChange(
                            filename = "file$it",
                            folderPath = "[Camera]/",
                            modifiedByName = "laptop",
                            timeMillis = null,
                            rawTime = "yesterday",
                            type = ChangeType.FILE,
                            action = ChangeAction.MODIFIED,
                            folderId = "cam-photos",
                            rawPath = "file$it",
                        )
                    },
                isRefreshing = false,
                showExactTimes = false,
                onNavigateBack = {},
                onRefresh = onRefresh,
                onToggleExactTimes = {},
                onItemClick = {},
                pullToRefresh = pullToRefresh,
            )
        }
    }

    private fun pullDown() {
        compose.onNodeWithText("file0").performTouchInput {
            swipeDown(startY = top, endY = top + 2000f, durationMillis = 500)
        }
        compose.waitForIdle()
    }

    @Test
    fun `pulling the list refreshes it`() {
        var refreshed = 0
        show(pullToRefresh = true) { refreshed++ }

        pullDown()

        assertEquals(1, refreshed)
    }

    @Test
    fun `without pull to refresh a pinch and drag does not refresh, the button does`() {
        var refreshed = 0
        show(pullToRefresh = false) { refreshed++ }

        pullDown()
        assertEquals(0, refreshed)

        compose.onNodeWithContentDescription("Refresh").performClick()
        assertEquals(1, refreshed)
    }

    private companion object {
        const val EMPTY = "There are no recent changes."
    }
}
