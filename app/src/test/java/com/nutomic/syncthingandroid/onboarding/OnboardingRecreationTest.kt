package com.nutomic.syncthingandroid.onboarding

import android.app.Application
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.testutil.ConfigFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Configuration change behaviour for onboarding: the current page and the page list are restored
 * through [OnboardingViewModel]'s [SavedStateHandle].
 */
@RunWith(AndroidJUnit4::class)
@Config(
    shadows = [com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess::class]
)
class OnboardingRecreationTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        System.setProperty("os.version", "6.1.0-android")
    }

    @Test
    fun `recreation keeps the current onboarding page`() {
        ConfigFixture.install(app)

        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            assertTrue(scenario.state.isAtLeast(Lifecycle.State.RESUMED))
            compose.onNodeWithText("Continue").performClick()
            compose.waitForIdle()

            val pageBefore = scenario.viewModelPage()
            assertTrue(pageBefore > 0)

            scenario.recreate()
            compose.waitForIdle()

            assertTrue(scenario.state.isAtLeast(Lifecycle.State.RESUMED))
            assertEquals(pageBefore, scenario.viewModelPage())
        }
    }

    @Test
    fun `recreation does not restart the app when the config is already present`() {
        ConfigFixture.install(app)

        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            compose.onNodeWithText("Continue").performClick()
            compose.waitForIdle()

            scenario.recreate()
            compose.waitForIdle()

            // If the activity had decided to skip onboarding it would have finished.
            assertTrue(scenario.state.isAtLeast(Lifecycle.State.RESUMED))
        }
    }

    private fun <T : ActivityScenario<OnboardingActivity>> T.viewModelPage(): Int {
        var page = -1
        onActivity { page = it.viewModel.uiState.value.currentPage }
        return page
    }
}
