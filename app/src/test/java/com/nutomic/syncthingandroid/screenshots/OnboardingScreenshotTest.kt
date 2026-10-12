package com.nutomic.syncthingandroid.screenshots

import com.github.takahirom.roborazzi.captureRoboImage
import com.nutomic.syncthingandroid.onboarding.OnboardingPage
import com.nutomic.syncthingandroid.onboarding.OnboardingScreen
import com.nutomic.syncthingandroid.onboarding.OnboardingUiState
import com.nutomic.syncthingandroid.theme.ApplicationTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Every onboarding page, before and after its permission is granted, in light and dark theme. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class OnboardingScreenshotTest(
    private val page: OnboardingPage,
    private val granted: Boolean,
    private val dark: Boolean,
) {

    @Test
    fun screenshot() {
        if (dark) RuntimeEnvironment.setQualifiers("+night")
        val pages = OnboardingPage.entries.toList()
        val state =
            OnboardingUiState(
                pages = pages,
                currentPage = pages.indexOf(page),
                hasStoragePermission = granted,
                hasIgnoreDozePermission = granted,
                hasLocationPermission = granted,
                hasLocalNetworkPermission = granted,
                hasNotificationPermission = granted,
                hasConfig = granted,
                keyGenerationStatus = if (granted) "Keys generated." else "",
            )
        val name = "${page.name.lowercase()}${if (granted) "-granted" else ""}"
        captureRoboImage(screenshotPath("onboarding", name, dark)) {
            ApplicationTheme {
                OnboardingScreen(
                    uiState = state,
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

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} granted={1} dark={2}")
        fun parameters(): List<Array<Any>> =
            OnboardingPage.entries.flatMap { page ->
                val grantedStates =
                    if (page == OnboardingPage.WELCOME) listOf(false) else listOf(false, true)
                grantedStates.flatMap { granted ->
                    listOf(arrayOf<Any>(page, granted, false), arrayOf<Any>(page, granted, true))
                }
            }
    }
}
