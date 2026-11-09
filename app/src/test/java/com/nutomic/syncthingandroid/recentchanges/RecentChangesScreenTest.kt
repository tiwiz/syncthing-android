package com.nutomic.syncthingandroid.recentchanges

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
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

    private fun show(isRefreshing: Boolean = false, onRefresh: () -> Unit = {}) {
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
                isRefreshing = isRefreshing,
                showExactTimes = false,
                onNavigateBack = {},
                onRefresh = onRefresh,
                onToggleExactTimes = {},
                onItemClick = {},
            )
        }
    }

    private fun onHeadset() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context.packageManager).setSystemFeature(XR_FEATURE, true)
    }

    private fun drag() {
        compose.onNodeWithText("file0").performTouchInput {
            swipeDown(startY = top, endY = top + 2000f, durationMillis = 500)
        }
        compose.waitForIdle()
    }

    private fun progressShown(): Boolean =
        compose
            .onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
            .fetchSemanticsNodes()
            .isNotEmpty()

    @Test
    fun `on a phone dragging the list down refreshes it`() {
        var refreshed = 0
        show { refreshed++ }

        drag()

        assertEquals(1, refreshed)
    }

    @Test
    fun `on a headset dragging the list does not refresh it, the button does`() {
        onHeadset()
        var refreshed = 0
        show { refreshed++ }

        drag()
        assertEquals(0, refreshed)

        compose.onNodeWithContentDescription("Refresh").performClick()
        assertEquals(1, refreshed)
    }

    @Test
    fun `on a headset a refresh shows a progress bar, as nothing else would`() {
        onHeadset()
        show(isRefreshing = true)

        assertTrue(progressShown())
    }

    @Test
    fun `on a headset no progress bar shows while idle`() {
        onHeadset()
        show(isRefreshing = false)

        assertFalse(progressShown())
    }

    @Test
    @Config(qualifiers = "television")
    fun `on TV dragging does not refresh and no bar shows`() {
        var refreshed = 0
        show(isRefreshing = true) { refreshed++ }

        drag()

        assertEquals(0, refreshed)
        assertFalse(progressShown())
    }

    private companion object {
        const val XR_FEATURE = "android.software.xr.api.spatial"
        const val EMPTY = "There are no recent changes."
    }
}
