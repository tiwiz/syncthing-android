package com.nutomic.syncthingandroid.service

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.nutomic.syncthingandroid.service.RunConditionMonitor.Companion.EXTRA_BEGIN_ACTIVE_TIME_WINDOW

/**
 * Scheduled by the JobScheduler, see JobUtils.scheduleSyncTriggerServiceJob. Tells
 * RunConditionMonitor to start Syncthing for a while, or to stop it.
 */
class SyncTriggerJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        val intent = Intent(RunConditionMonitor.ACTION_SYNC_TRIGGER_FIRED)
        // Without the flag, Syncthing stops.
        if (params.extras.getInt(EXTRA_BEGIN_ACTIVE_TIME_WINDOW, 0) == 1) {
            intent.putExtra(EXTRA_BEGIN_ACTIVE_TIME_WINDOW, true)
        }
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
