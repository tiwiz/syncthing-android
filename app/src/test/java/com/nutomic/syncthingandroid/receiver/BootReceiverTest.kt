package com.nutomic.syncthingandroid.receiver

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.SyncthingService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** [BootReceiver] starts Syncthing after a reboot or an app update, if the user asked for it. */
@RunWith(AndroidJUnit4::class)
class BootReceiverTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun startOnBoot(enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(app)
            .edit()
            .putBoolean(Constants.PREF_START_SERVICE_ON_BOOT, enabled)
            .commit()
    }

    private fun receive(action: String) = BootReceiver().onReceive(app, Intent(action))

    private fun startedService(): ComponentName? = shadowOf(app).nextStartedService?.component

    @Test
    fun `boot and app update start the service when start on boot is on`() {
        startOnBoot(true)

        receive(Intent.ACTION_BOOT_COMPLETED)
        assertEquals(ComponentName(app, SyncthingService::class.java), startedService())

        receive(Intent.ACTION_MY_PACKAGE_REPLACED)
        assertEquals(ComponentName(app, SyncthingService::class.java), startedService())
    }

    @Test
    fun `nothing starts when start on boot is off`() {
        startOnBoot(false)

        receive(Intent.ACTION_BOOT_COMPLETED)
        receive(Intent.ACTION_MY_PACKAGE_REPLACED)

        assertNull(startedService())
    }

    @Test
    fun `other broadcasts are ignored`() {
        startOnBoot(true)

        receive(Intent.ACTION_LOCKED_BOOT_COMPLETED)

        assertNull(startedService())
    }

    @Test
    fun `the manifest delivers boot and update broadcasts to it`() {
        listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED).forEach { action ->
            val receivers =
                app.packageManager.queryBroadcastReceivers(
                    Intent(action).setPackage(app.packageName),
                    0,
                )
            assertEquals(
                action,
                listOf(BootReceiver::class.java.name),
                receivers.map { it.activityInfo.name },
            )
        }
    }
}
