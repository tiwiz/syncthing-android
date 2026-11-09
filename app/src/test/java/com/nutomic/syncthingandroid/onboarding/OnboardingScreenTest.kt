package com.nutomic.syncthingandroid.onboarding

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
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
        // A window laid out at zero width first leaves the pager on its last page while the flow
        // stays on the first. On an Android XR headset the pager made the same jump for another,
        // unknown reason; either way key generation never started.
        var width by mutableStateOf(0.dp)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            controller.get().setContent { Screen(width) }
            compose.waitForIdle()

            width = 800.dp
            compose.waitForIdle()

            compose.onNodeWithText("Key Generation").assertDoesNotExist()
            compose.onNodeWithText("Syncthing for Android", substring = true).assertExists()
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Composable
    private fun Screen(width: Dp) {
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
}
