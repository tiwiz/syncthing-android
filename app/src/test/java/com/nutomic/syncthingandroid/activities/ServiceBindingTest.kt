package com.nutomic.syncthingandroid.activities

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.recentchanges.RecentChangesActivity
import com.nutomic.syncthingandroid.service.SyncthingConnection
import com.nutomic.syncthingandroid.testutil.ConfigFixture
import com.nutomic.syncthingandroid.testutil.ShadowContentResolverWithStatusListeners
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import com.nutomic.syncthingandroid.testutil.ShadowUtilDeviceShell
import com.nutomic.syncthingandroid.testutil.StoppedSyncthingService
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Screens reach Syncthing only through [SyncthingConnection]: those that do not need it bind
 * nothing, and those that do let go of it once they are gone.
 */
@RunWith(AndroidJUnit4::class)
@Config(
    qualifiers = "en-rUS",
    shadows =
        [
            ShadowEnvironmentWithAllFilesAccess::class,
            ShadowContentResolverWithStatusListeners::class,
            ShadowUtilDeviceShell::class,
        ],
    instrumentedPackages = ["com.nutomic.syncthingandroid.util"],
)
class ServiceBindingTest {

    // Drives Compose on its own clock, so animations on screen do not keep the looper busy.
    @get:Rule val compose = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        System.setProperty("os.version", "6.1.0-android")
        ConfigFixture.install(app)
        StoppedSyncthingService.install()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun bound() = shadowOf(app).boundServiceConnections.size

    private fun unbound() = shadowOf(app).unboundServiceConnections.size

    @Test
    fun `screens that do not need Syncthing do not bind it`() {
        val screens =
            listOf(
                Intent(app, LogActivity::class.java),
                FolderPickerActivity.createIntent(app, null, app.cacheDir.path),
                SyncConditionsActivity.createIntent(app, "sc_folder_cam-photos", "Camera"),
                Intent(app, WebViewActivity::class.java)
                    .putExtra(WebViewActivity.EXTRA_WEB_URL, "https://data.syncthing.net/"),
            )
        for (intent in screens) {
            ActivityScenario.launch<android.app.Activity>(intent).use {
                compose.waitForIdle()
                assertEquals(intent.component?.className, 0, bound())
            }
        }
    }

    @Test
    fun `a screen that needs Syncthing keeps it bound briefly after it closes, then lets go`() {
        ActivityScenario.launch(RecentChangesActivity::class.java).use { idle() }
        assertEquals(1, bound())
        // A rotation in between would not bind again.
        assertEquals(0, unbound())

        shadowOf(Looper.getMainLooper())
            .idleFor(Duration.ofMillis(SyncthingConnection.STOP_TIMEOUT_MILLIS + 1))

        assertEquals(1, unbound())
    }

    @Test
    fun `opening a screen from a consent notification cancels it`() {
        val notifications = app.getSystemService(NotificationManager::class.java)
        notifications.notify(NOTIFICATION_ID, Notification.Builder(app, "test").build())

        ActivityScenario.launch<android.app.Activity>(
                FolderActivity.createIntent(app)
                    .putExtra(FolderActivity.EXTRA_NOTIFICATION_ID, NOTIFICATION_ID)
            )
            .use { idle() }

        assertEquals(0, shadowOf(notifications).size())
    }

    private companion object {
        const val NOTIFICATION_ID = 42
    }
}
