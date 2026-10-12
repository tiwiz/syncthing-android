package com.nutomic.syncthingandroid.screenshots

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.activities.DeviceActivity
import com.nutomic.syncthingandroid.activities.FolderActivity
import com.nutomic.syncthingandroid.activities.MainActivity
import com.nutomic.syncthingandroid.testutil.ConfigFixture
import com.nutomic.syncthingandroid.testutil.ConfigFixture.REMOTE_DEVICE_ID
import com.nutomic.syncthingandroid.testutil.ShadowContentResolverWithStatusListeners
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import com.nutomic.syncthingandroid.testutil.StoppedSyncthingService
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The View-based screens, with the fixture config and Syncthing not running, in light and dark
 * theme. Folder and device screens are shown both when creating and when editing.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    qualifiers = SCREENSHOT_QUALIFIERS,
    shadows =
        [ShadowEnvironmentWithAllFilesAccess::class, ShadowContentResolverWithStatusListeners::class],
)
class ActivityScreenshotTest(private val screen: Screen, private val dark: Boolean) {

    enum class Screen(val activity: Class<out Activity>, val extras: Intent.() -> Unit = {}) {
        MAIN(MainActivity::class.java),
        FOLDER_NEW(FolderActivity::class.java, { putExtra(FolderActivity.EXTRA_IS_CREATE, true) }),
        FOLDER_EDIT(
            FolderActivity::class.java,
            {
                putExtra(FolderActivity.EXTRA_IS_CREATE, false)
                putExtra(FolderActivity.EXTRA_FOLDER_ID, "cam-photos")
            },
        ),
        DEVICE_NEW(DeviceActivity::class.java, { putExtra(DeviceActivity.EXTRA_IS_CREATE, true) }),
        DEVICE_EDIT(
            DeviceActivity::class.java,
            {
                putExtra(DeviceActivity.EXTRA_IS_CREATE, false)
                putExtra(DeviceActivity.EXTRA_DEVICE_ID, REMOTE_DEVICE_ID)
            },
        ),
    }

    @Test
    fun screenshot() {
        System.setProperty("os.version", "6.1.0-android")
        if (dark) RuntimeEnvironment.setQualifiers("+night")
        val context = ApplicationProvider.getApplicationContext<Context>()
        ConfigFixture.install(context)
        StoppedSyncthingService.install()
        val intent = Intent(context, screen.activity).apply(screen.extras)
        ActivityScenario.launch<Activity>(intent).use { scenario ->
            settle()
            scenario.onActivity {
                if (screen == Screen.FOLDER_NEW) {
                    // The app proposes a random folder id; pin it for the screenshot.
                    it.findViewById<EditText>(R.id.id).setText("abcde-fghij")
                }
                it.window.decorView.hideTextCursors()
                settle(rounds = 2)
                it.window.decorView.captureRoboImage(
                    screenshotPath("activities", screen.name.lowercase(), dark)
                )
            }
        }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} dark={1}")
        fun parameters(): List<Array<Any>> =
            Screen.entries.flatMap { listOf(arrayOf<Any>(it, false), arrayOf<Any>(it, true)) }
    }
}
