package com.nutomic.syncthingandroid.onboarding

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.net.toUri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.testutil.ConfigFixture.LOCAL_DEVICE_ID
import com.nutomic.syncthingandroid.testutil.FakeSyncthingBinary
import com.nutomic.syncthingandroid.testutil.Fixtures
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * The battery optimization page on a first launch: skipping it needs a confirmation, and its button
 * asks Android for the exemption where Android offers it.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-rUS", shadows = [ShadowEnvironmentWithAllFilesAccess::class])
class BatteryOptimizationPageTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        System.setProperty("os.version", "6.1.0-android")
        // A first launch: no config yet, so the pages after this one end in key generation.
        FakeSyncthingBinary(app)
            .apply {
                generatedConfig = Fixtures.text("syncthing/config.xml")
                stdout = "$LOCAL_DEVICE_ID\n"
            }
            .install()
        shadowOf(app)
            .grantPermissions(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        shadowOf(app.getSystemService(PowerManager::class.java))
            .setIgnoringBatteryOptimizations(app.packageName, false)
    }

    /** Opens onboarding and goes past the welcome page. */
    private fun launch() =
        ActivityScenario.launch(OnboardingActivity::class.java).also {
            button("Continue").performClick()
            compose.onNodeWithText("Battery Optimization").assertExists()
        }

    /** The button labelled [text] on the page shown; the pager keeps its neighbours composed. */
    private fun button(text: String): SemanticsNodeInteraction {
        val matcher = hasText(text) and hasClickAction()
        // The widest root is the screen; a dialog adds a smaller one.
        val width = compose.onAllNodes(isRoot()).fetchSemanticsNodes().maxOf { it.size.width }
        val nodes = compose.onAllNodes(matcher).fetchSemanticsNodes()
        return compose
            .onAllNodes(matcher)[
                nodes.indexOfFirst {
                    val bounds = it.boundsInRoot
                    bounds.width > 0 && bounds.left >= 0 && bounds.right <= width
                }]
    }

    @Test
    fun `skipping the page needs a confirmation`() {
        launch().use {
            button("Continue").performClick()
            compose.onNodeWithText("Really skip?", substring = true).assertExists()

            button("No").performClick()
            compose.onNodeWithText("Battery Optimization").assertExists()
            assertNull(shadowOf(app).nextStartedActivity)

            button("Continue").performClick()
            button("Yes").performClick()
            compose.onNodeWithText("Key Generation").assertExists()
        }
    }

    @Test
    fun `the button asks Android for the exemption`() {
        val request =
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData("package:${app.packageName}".toUri())
        val settings =
            ResolveInfo().apply {
                activityInfo =
                    ActivityInfo().apply {
                        packageName = "com.android.settings"
                        name = "RequestIgnoreBatteryOptimizations"
                    }
            }
        shadowOf(app.packageManager).addResolveInfoForIntent(request, settings)

        launch().use {
            button("Grant permission").performClick()

            val intent: Intent? = shadowOf(app).nextStartedActivity
            assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, intent?.action)
            assertEquals("package:${app.packageName}", intent?.dataString)
        }
    }

    @Test
    fun `without the exemption screen the button says it is not supported`() {
        launch().use {
            button("Grant permission").performClick()

            assertEquals(
                "Your device does not support disabling battery optimizations",
                ShadowToast.getTextOfLatestToast(),
            )
        }
    }
}
