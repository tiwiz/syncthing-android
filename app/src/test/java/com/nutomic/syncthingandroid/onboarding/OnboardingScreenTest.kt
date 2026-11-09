package com.nutomic.syncthingandroid.onboarding

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.theme.ApplicationTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/** Which page the onboarding pager shows. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-rUS")
class OnboardingScreenTest {

    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun `a window that starts with no width still shows the current page`() {
        // Android XR lays the window out at zero width first; the pager then settled on the last
        // page while the flow stayed on the first, so key generation never started.
        var width by mutableStateOf(0.dp)
        Robolectric.buildActivity(ComponentActivity::class.java).setup().get().setContent {
            ApplicationTheme {
                Box(Modifier.width(width).fillMaxHeight()) {
                    OnboardingScreen(
                        uiState = OnboardingUiState(pages = OnboardingPage.entries.toList()),
                        onBack = {},
                        onContinue = {},
                        onFinishOnboarding = {},
                        onGrantLocationPermission = {},
                        onGrantLocalNetworkPermission = {},
                        onGrantNotificationPermission = {},
                    )
                }
            }
        }
        compose.waitForIdle()

        width = 800.dp
        compose.waitForIdle()

        compose.onNodeWithText("Key Generation").assertDoesNotExist()
        compose.onNodeWithText("Syncthing for Android", substring = true).assertExists()
    }
}
