package com.nutomic.syncthingandroid.util

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import android.util.Log
import com.nutomic.syncthingandroid.service.RunConditionMonitor.Companion.EXTRA_BEGIN_ACTIVE_TIME_WINDOW
import com.nutomic.syncthingandroid.service.SyncTriggerJobService

object JobUtils {
    private const val TAG = "JobUtils"
    private const val MILLIS_PER_SECOND = 1000L

    /**
     * Schedules SyncTriggerJobService to run in [delayInSeconds] (0 when negative). It starts
     * Syncthing if [startRun], otherwise stops it.
     */
    @JvmStatic
    fun scheduleSyncTriggerServiceJob(context: Context, delayInSeconds: Int, startRun: Boolean) {
        val delay = delayInSeconds.coerceAtLeast(0)
        val builder =
            JobInfo.Builder(0, ComponentName(context, SyncTriggerJobService::class.java))
                // Wait at least "delayInSeconds".
                .setMinimumLatency(delay * MILLIS_PER_SECOND)
        // The PersistableBundle forwards the start flag to the SyncTriggerJobService.
        if (startRun) {
            builder.setExtras(
                PersistableBundle().apply {
                    // Must be an int: a boolean needs API 22.
                    putInt(EXTRA_BEGIN_ACTIVE_TIME_WINDOW, 1)
                }
            )
        }
        context.getSystemService(JobScheduler::class.java).schedule(builder.build())
        Log.i(TAG, "Scheduled SyncTriggerJobService to run in $delay seconds.")
    }

    @JvmStatic
    fun cancelAllScheduledJobs(context: Context) {
        context.getSystemService(JobScheduler::class.java).cancelAll()
    }
}
