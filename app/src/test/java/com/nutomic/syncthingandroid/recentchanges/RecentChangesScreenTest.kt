package com.nutomic.syncthingandroid.recentchanges

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
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

    private companion object {
        const val EMPTY = "There are no recent changes."
    }
}
