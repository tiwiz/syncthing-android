package com.nutomic.syncthingandroid.util

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.provider.Settings
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.service.AppPrefs
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.RunConditionMonitor
import com.nutomic.syncthingandroid.service.SyncTriggerJobService
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/** Small helpers: sync trigger jobs, the storage permission and the app preferences. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-rUS")
class HelpersTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val jobScheduler = context.getSystemService(JobScheduler::class.java)

    // JobUtils

    @Test
    fun `a sync trigger job waits for the delay and says whether to start`() {
        JobUtils.scheduleSyncTriggerServiceJob(context, 90, true)

        val job = jobScheduler.allPendingJobs.single()
        assertEquals(SyncTriggerJobService::class.java.name, job.service.className)
        assertEquals(90_000L, job.minLatencyMillis)
        assertEquals(1, job.extras.getInt(RunConditionMonitor.EXTRA_BEGIN_ACTIVE_TIME_WINDOW))
    }

    @Test
    fun `a stop job carries no start flag and a negative delay means now`() {
        JobUtils.scheduleSyncTriggerServiceJob(context, -5, false)

        val job = jobScheduler.allPendingJobs.single()
        assertEquals(0L, job.minLatencyMillis)
        assertFalse(job.extras.containsKey(RunConditionMonitor.EXTRA_BEGIN_ACTIVE_TIME_WINDOW))
    }

    @Test
    fun `scheduled jobs can all be cancelled`() {
        JobUtils.scheduleSyncTriggerServiceJob(context, 10, true)

        JobUtils.cancelAllScheduledJobs(context)

        assertTrue(jobScheduler.allPendingJobs.isEmpty())
    }

    // PermissionUtil

    @Test
    @Config(sdk = [29])
    fun `before Android 11 the storage permission is the write permission`() {
        assertFalse(PermissionUtil.haveStoragePermission(context))

        shadowOf(context as Application)
            .grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)

        assertTrue(PermissionUtil.haveStoragePermission(context))
    }

    @Test
    @Config(shadows = [ShadowEnvironmentWithAllFilesAccess::class])
    fun `from Android 11 the storage permission is all files access`() {
        assertTrue(PermissionUtil.haveStoragePermission(context))
    }

    @Test
    @Config(sdk = [29])
    fun `before Android 11 the write permission is requested`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        PermissionUtil.requestStoragePermission(activity, 42)

        val request = checkNotNull(shadowOf(activity).lastRequestedPermission)
        assertEquals(42, request.requestCode)
        assertEquals(
            listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
            request.requestedPermissions.toList(),
        )
    }

    @Test
    fun `from Android 11 the all files access screen opens for this app`() {
        val intent =
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                .setData(Uri.parse("package:${context.packageName}"))
        shadowOf(context.packageManager)
            .addResolveInfoForIntent(
                intent,
                android.content.pm.ResolveInfo().apply {
                    activityInfo =
                        ActivityInfo().apply {
                            packageName = "com.android.settings"
                            name = "AllFilesAccess"
                        }
                },
            )
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        PermissionUtil.requestStoragePermission(activity, 42)

        val started = checkNotNull(shadowOf(activity).nextStartedActivity)
        assertEquals(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, started.action)
        assertEquals(Uri.parse("package:${context.packageName}"), started.data)
        assertNull(ShadowToast.getLatestToast())
    }

    @Test
    fun `without an all files access screen a message is shown`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        PermissionUtil.requestStoragePermission(activity, 42)

        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(
            "Your device does not support all files access",
            ShadowToast.getTextOfLatestToast(),
        )
    }

    // AppPrefs

    @Test
    fun `verbose logging is off unless enabled`() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        assertFalse(AppPrefs.getPrefVerboseLog(context))

        prefs.edit().putBoolean(Constants.PREF_VERBOSE_LOG, true).commit()

        assertTrue(AppPrefs.getPrefVerboseLog(context))
        assertTrue(AppPrefs.getPrefVerboseLog(prefs))
    }

    @Test
    fun `verbose logging is off without a context or preferences`() {
        assertFalse(AppPrefs.getPrefVerboseLog(null as Context?))
        assertFalse(AppPrefs.getPrefVerboseLog(null as android.content.SharedPreferences?))
    }

    @Test
    fun `starting on boot is off unless enabled`() {
        assertFalse(AppPrefs.getStartServiceOnBoot(context))

        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putBoolean(Constants.PREF_START_SERVICE_ON_BOOT, true)
            .commit()

        assertTrue(AppPrefs.getStartServiceOnBoot(context))
    }
}
