package com.nutomic.syncthingandroid.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.activities.DeviceActivity
import com.nutomic.syncthingandroid.activities.FolderActivity
import com.nutomic.syncthingandroid.activities.LogActivity
import com.nutomic.syncthingandroid.onboarding.OnboardingActivity
import com.nutomic.syncthingandroid.testutil.ConfigFixture
import com.nutomic.syncthingandroid.testutil.ConfigFixture.REMOTE_DEVICE_ID
import com.nutomic.syncthingandroid.testutil.RunningSyncthingService
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import com.nutomic.syncthingandroid.testutil.ShadowUtilDeviceShell
import com.nutomic.syncthingandroid.testutil.StoppedSyncthingService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The notifications: the persistent one in every service state (foreground or not), crashes, the
 * missing storage permission, and the consent notifications for devices and folders.
 */
@RunWith(AndroidJUnit4::class)
@Config(
    qualifiers = "en-rUS",
    shadows = [ShadowEnvironmentWithAllFilesAccess::class, ShadowUtilDeviceShell::class],
    instrumentedPackages = ["com.nutomic.syncthingandroid.util"],
)
class NotificationHandlerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private lateinit var service: SyncthingService
    private lateinit var handler: NotificationHandler

    @Before
    fun setUp() {
        System.setProperty("os.version", "6.1.0-android")
        ConfigFixture.install(context)
        service = StoppedSyncthingService.install()
        handler = NotificationHandler(context)
        manager.cancelAll()
    }

    private fun foreground(): Notification? = shadowOf(service).lastForegroundNotification

    private fun foregroundId(): Int = shadowOf(service).lastForegroundNotificationId

    private fun title(notification: Notification?): String? =
        notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    private fun text(notification: Notification?): String? =
        notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    private fun setState(state: SyncthingService.State) =
        RunningSyncthingService.setState(service, state)

    @Test
    fun `the three channels exist`() {
        assertEquals(
            setOf(
                "01_syncthing_persistent",
                "02_syncthing_notifications",
                "03_syncthing_persistent_waiting",
            ),
            manager.notificationChannels.map { it.id }.toSet(),
        )
        assertEquals(
            NotificationManager.IMPORTANCE_MIN,
            manager.getNotificationChannel("01_syncthing_persistent").importance,
        )
        assertEquals(
            NotificationManager.IMPORTANCE_LOW,
            manager.getNotificationChannel("02_syncthing_notifications").importance,
        )
    }

    @Test
    fun `while waiting for run conditions the app stays in the foreground with a sleeping notification`() {
        setState(SyncthingService.State.DISABLED)

        handler.updatePersistentNotification(service)

        assertEquals(4, foregroundId())
        assertEquals("Sleeping", title(foreground()))
        assertEquals("03_syncthing_persistent_waiting", foreground()?.channelId)
        assertTrue(foreground()!!.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(1, foreground()!!.actions.size)
        assertEquals("Exit", foreground()!!.actions[0].title.toString())
    }

    @Test
    fun `starting and terminated texts`() {
        setState(SyncthingService.State.STARTING)
        handler.updatePersistentNotification(service)
        assertEquals(1, foregroundId())
        assertEquals("Syncthing is starting.", title(foreground()))

        setState(SyncthingService.State.ERROR)
        handler.updatePersistentNotification(service)
        assertEquals("Terminated", title(foreground()))
    }

    @Test
    fun `an active Syncthing shows devices and progress`() {
        setState(SyncthingService.State.ACTIVE)

        handler.updatePersistentNotification(service, false, 0, -1)
        assertEquals("Running: No remote devices connected", title(foreground()))
        assertEquals(1, foregroundId())

        handler.updatePersistentNotification(service, false, 2, 100)
        assertEquals("Running: 2 devices online and up to date", title(foreground()))

        handler.updatePersistentNotification(service, false, 1, 42)
        assertEquals("Syncing: 42% complete, 1 device online", title(foreground()))

        // The short form keeps the last details.
        handler.updatePersistentNotification(service)
        assertEquals("Syncing: 42% complete, 1 device online", title(foreground()))
    }

    @Test
    fun `during shutdown the persistent notification is removed`() {
        setState(SyncthingService.State.ACTIVE)
        handler.updatePersistentNotification(service, false, 0, -1)
        manager.notify(1, NotificationCompat.Builder(context, "01_syncthing_persistent").build())

        handler.setAppShutdownInProgress(true)
        handler.updatePersistentNotification(service)

        assertNull(shadowOf(manager).getNotification(1))
        assertNull(shadowOf(manager).getNotification(4))
    }

    @Test
    fun `a crash notification opens the log`() {
        handler.showCrashedNotification(R.string.executable_not_found, "libsyncthingnative.so")

        val notification = checkNotNull(shadowOf(manager).getNotification(9))
        assertEquals(
            "Core executable \"libsyncthingnative.so\" is missing. Check build and logcat output.",
            title(notification),
        )
        assertEquals("Click for logs (libsyncthingnative.so)", text(notification))
        assertEquals(
            LogActivity::class.java.name,
            shadowOf(notification.contentIntent).savedIntent.component?.className,
        )
    }

    @Test
    fun `a revoked storage permission leads to the onboarding`() {
        handler.showStoragePermissionRevokedNotification()

        val notification = checkNotNull(shadowOf(manager).getNotification(10))
        assertEquals("Terminated", title(notification))
        assertEquals("Write storage permission is required for this app", text(notification))
        assertEquals(
            OnboardingActivity::class.java.name,
            shadowOf(notification.contentIntent).savedIntent.component?.className,
        )
    }

    @Test
    fun `consent notification ids derive from the text`() {
        val id = handler.getNotificationIdFromText("hello")

        assertEquals(1000 + "hello".hashCode() % 1000, id)
        assertEquals(id, handler.getNotificationIdFromText("hello"))
    }

    @Test
    fun `a device asking to connect can be accepted or ignored`() {
        handler.showDeviceConnectNotification(REMOTE_DEVICE_ID, "", "tcp://192.0.2.1:22000")

        val title = "Device \"${REMOTE_DEVICE_ID.substring(0, 7)}\" wants to connect"
        val id = handler.getNotificationIdFromText(title)
        val notification = checkNotNull(shadowOf(manager).getNotification(id))
        assertEquals(title, text(notification))
        assertEquals(listOf("Accept", "Ignore"), notification.actions.map { it.title.toString() })

        val accept = intentOf(notification.actions[0].actionIntent)
        assertEquals(DeviceActivity::class.java.name, accept.component?.className)
        assertEquals(REMOTE_DEVICE_ID, accept.getStringExtra(DeviceActivity.EXTRA_DEVICE_ID))
        assertEquals(id, accept.getIntExtra(DeviceActivity.EXTRA_NOTIFICATION_ID, 0))
        assertTrue(accept.getBooleanExtra(DeviceActivity.EXTRA_IS_CREATE, false))

        val ignore = intentOf(notification.actions[1].actionIntent)
        assertEquals(SyncthingService.ACTION_IGNORE_DEVICE, ignore.action)
        assertEquals(
            "tcp://192.0.2.1:22000",
            ignore.getStringExtra(SyncthingService.EXTRA_DEVICE_ADDRESS),
        )

        handler.cancelConsentNotification(id)
        assertNull(shadowOf(manager).getNotification(id))
    }

    @Test
    fun `each consent notification ignores its own device`() {
        handler.showDeviceConnectNotification("AAAAAAA-device", "phone", null)
        handler.showDeviceConnectNotification("BBBBBBB-device", "tablet", null)

        val first =
            checkNotNull(
                shadowOf(manager)
                    .getNotification(
                        handler.getNotificationIdFromText("Device \"phone\" wants to connect")
                    )
            )
        val ignore = intentOf(first.actions[1].actionIntent)
        assertEquals("AAAAAAA-device", ignore.getStringExtra(SyncthingService.EXTRA_DEVICE_ID))
    }

    @Test
    fun `a folder offered by a device can be accepted or ignored`() {
        handler.showFolderShareNotification(
            REMOTE_DEVICE_ID,
            "laptop",
            "music",
            "Music",
            true,
            false,
            true,
        )

        val title = "Device \"laptop\" wants to share folder \"Music (music)\""
        val notification =
            checkNotNull(
                shadowOf(manager).getNotification(handler.getNotificationIdFromText(title))
            )
        assertEquals(title, text(notification))
        val accept = intentOf(notification.actions[0].actionIntent)
        assertEquals(FolderActivity::class.java.name, accept.component?.className)
        assertEquals("music", accept.getStringExtra(FolderActivity.EXTRA_FOLDER_ID))
        assertTrue(accept.getBooleanExtra(FolderActivity.EXTRA_RECEIVE_ENCRYPTED, false))
        assertEquals(
            SyncthingService.ACTION_IGNORE_FOLDER,
            intentOf(notification.actions[1].actionIntent).action,
        )
    }

    @Test
    fun `a folder without a label is named by its id, and missing ids show nothing`() {
        handler.showFolderShareNotification(
            REMOTE_DEVICE_ID,
            "laptop",
            "music",
            "",
            false,
            false,
            true,
        )
        handler.showFolderShareNotification(null, "laptop", "x", "", false, false, true)
        handler.showFolderShareNotification(
            REMOTE_DEVICE_ID,
            "laptop",
            null,
            "",
            false,
            false,
            true,
        )
        handler.showDeviceConnectNotification(null, "laptop", "")

        assertEquals(
            listOf("Device \"laptop\" wants to share folder \"music\""),
            shadowOf(manager).allNotifications.map { text(it) },
        )
    }

    @Test
    fun `cancelling consent id 0 does nothing`() {
        manager.notify(0, NotificationCompat.Builder(context, "02_syncthing_notifications").build())

        handler.cancelConsentNotification(0)

        assertEquals(1, shadowOf(manager).allNotifications.size)
    }

    private fun intentOf(pendingIntent: PendingIntent): Intent = shadowOf(pendingIntent).savedIntent
}
