package com.nutomic.syncthingandroid.activities

import android.app.Application
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.onboarding.OnboardingActivity
import com.nutomic.syncthingandroid.testutil.ConfigFixture
import com.nutomic.syncthingandroid.testutil.ShadowContentResolverWithStatusListeners
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import com.nutomic.syncthingandroid.testutil.StoppedSyncthingService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The main screen after the user revoked "All files access": the app goes back to the onboarding to
 * ask again.
 */
@RunWith(AndroidJUnit4::class)
@Config(
    qualifiers = "en-rUS",
    shadows =
        [
            ShadowEnvironmentWithAllFilesAccess::class,
            ShadowContentResolverWithStatusListeners::class,
        ],
)
class MainActivityPermissionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        ShadowEnvironmentWithAllFilesAccess.allFilesAccess = true
    }

    @Test
    fun `a revoked storage permission goes back to the onboarding`() {
        System.setProperty("os.version", "6.1.0-android")
        ConfigFixture.install(context)
        StoppedSyncthingService.install()
        ShadowEnvironmentWithAllFilesAccess.allFilesAccess = false

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val started = generateSequence {
                shadowOf(context as Application).nextStartedActivity
            }
                .firstOrNull { it.component?.className == OnboardingActivity::class.java.name }
            assertEquals(OnboardingActivity::class.java.name, started?.component?.className)
            scenario.onActivity { assertTrue(it.isFinishing) }
        }
    }
}
