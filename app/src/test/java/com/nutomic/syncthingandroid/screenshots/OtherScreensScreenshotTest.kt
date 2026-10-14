package com.nutomic.syncthingandroid.screenshots

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.fragment.app.FragmentActivity
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.nutomic.syncthingandroid.activities.FolderTypeDialogActivity
import com.nutomic.syncthingandroid.activities.LogActivity
import com.nutomic.syncthingandroid.activities.MainActivity
import com.nutomic.syncthingandroid.activities.PhotoShootActivity
import com.nutomic.syncthingandroid.activities.PullOrderDialogActivity
import com.nutomic.syncthingandroid.activities.QRScannerActivity
import com.nutomic.syncthingandroid.activities.ShareActivity
import com.nutomic.syncthingandroid.activities.SyncConditionsActivity
import com.nutomic.syncthingandroid.fragments.DeviceIdDialogFragment
import com.nutomic.syncthingandroid.recentchanges.RecentChangesActivity
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.testutil.ConfigFixture
import com.nutomic.syncthingandroid.testutil.ConfigFixture.REMOTE_DEVICE_ID
import com.nutomic.syncthingandroid.testutil.FakeSyncthing
import com.nutomic.syncthingandroid.testutil.RunningSyncthingService
import com.nutomic.syncthingandroid.testutil.ShadowContentResolverWithStatusListeners
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import com.nutomic.syncthingandroid.testutil.ShadowUtilDeviceShell
import java.util.TimeZone
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The remaining screens, with Syncthing running ([RunningSyncthingService]), in light and dark
 * theme. The WebView screens (web GUI, usage statistics) are left out: Robolectric does not draw
 * web content. Times are shown in UTC.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    qualifiers = SCREENSHOT_QUALIFIERS,
    shadows =
        [
            ShadowEnvironmentWithAllFilesAccess::class,
            ShadowContentResolverWithStatusListeners::class,
            ShadowUtilDeviceShell::class,
        ],
    instrumentedPackages = ["com.nutomic.syncthingandroid.util"],
)
class OtherScreensScreenshotTest(private val screen: Screen, private val dark: Boolean) {

    enum class Screen(
        val activity: Class<out Activity>,
        val extras: Intent.(Context) -> Unit = {},
        /** Runs on the launched activity before the capture, e.g. to open a dialog. */
        val onLaunched: (Activity) -> Unit = {},
        /** Dialogs live in their own window, so capture the whole screen for them. */
        val wholeScreen: Boolean = false,
    ) {
        LOG(LogActivity::class.java),
        SYNC_CONDITIONS(
            SyncConditionsActivity::class.java,
            extras = { context ->
                putExtras(
                    SyncConditionsActivity.createIntent(context, "sc_folder_cam-photos", "Camera")
                )
            },
        ),
        PHOTO_SHOOT(PhotoShootActivity::class.java),
        QR_SCANNER(QRScannerActivity::class.java),
        SHARE(
            ShareActivity::class.java,
            extras = {
                action = Intent.ACTION_SEND
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///storage/emulated/0/notes.txt"))
            },
        ),
        RECENT_CHANGES(RecentChangesActivity::class.java),
        FOLDER_TYPE(
            FolderTypeDialogActivity::class.java,
            extras = {
                putExtra(
                    FolderTypeDialogActivity.EXTRA_FOLDER_TYPE,
                    Constants.FOLDER_TYPE_SEND_ONLY,
                )
            },
            wholeScreen = true,
        ),
        PULL_ORDER(
            PullOrderDialogActivity::class.java,
            extras = { putExtra(PullOrderDialogActivity.EXTRA_PULL_ORDER, "oldestFirst") },
            wholeScreen = true,
        ),
        DEVICE_ID_DIALOG(
            MainActivity::class.java,
            onLaunched = {
                DeviceIdDialogFragment.show(
                    (it as FragmentActivity).supportFragmentManager,
                    "laptop",
                    REMOTE_DEVICE_ID,
                )
            },
            wholeScreen = true,
        ),
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val savedTimeZone = TimeZone.getDefault()
    private lateinit var syncthing: FakeSyncthing

    @After
    fun tearDown() {
        syncthing.close()
        TimeZone.setDefault(savedTimeZone)
    }

    @Test
    fun screenshot() {
        System.setProperty("os.version", "6.1.0-android")
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        if (dark) RuntimeEnvironment.setQualifiers("+night")
        ConfigFixture.install(context)
        // Recent changes: absolute times, which do not depend on when the test runs.
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putBoolean(Constants.PREF_SHOW_EXACT_TIMES, true)
            .commit()
        // The photo screen closes itself on devices without a camera.
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, true)
        syncthing = FakeSyncthing(context)
        RunningSyncthingService.install(syncthing)
        val intent = Intent(context, screen.activity).apply { screen.extras(this, context) }
        ActivityScenario.launch<Activity>(intent).use { scenario ->
            settle()
            scenario.onActivity(screen.onLaunched)
            settle()
            scenario.onActivity {
                it.window.decorView.hideTextCursors()
                settle(rounds = 2)
                val path = screenshotPath("other", screen.name.lowercase(), dark)
                if (screen.wholeScreen) captureScreenRoboImage(path)
                else it.window.decorView.captureRoboImage(path)
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
