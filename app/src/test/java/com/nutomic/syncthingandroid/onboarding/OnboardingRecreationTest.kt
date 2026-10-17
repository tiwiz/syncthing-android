package com.nutomic.syncthingandroid.onboarding

import android.Manifest
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
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
    fun `recreation stays in onboarding once every requirement is met`() {
        ConfigFixture.install(app)

        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            compose.onNodeWithText("Continue").performClick()
            compose.waitForIdle()
            // The last missing requirement is granted while onboarding is shown: only the first
            // launch skips to the main screen, a rotation must not.
            shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            // Back from the system settings: the screen sees the new permission.
            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.moveToState(Lifecycle.State.RESUMED)

            shadowOf(app).clearNextStartedActivities()

            scenario.recreate()
            compose.waitForIdle()

            // Skipping onboarding would have started the main screen.
            assertNull(shadowOf(app).nextStartedActivity)
        }
    }

    private fun <T : ActivityScenario<OnboardingActivity>> T.viewModelPage(): Int {
        var page = -1
        onActivity { page = it.viewModel.uiState.value.currentPage }
        return page
    }
}
