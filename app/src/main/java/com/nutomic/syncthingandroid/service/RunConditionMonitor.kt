package com.nutomic.syncthingandroid.service

import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.SyncStatusObserver
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.appDependencies
import com.nutomic.syncthingandroid.util.JobUtils
import java.util.Locale

/**
 * Decides whether Syncthing should run, from the run condition preferences and the device state,
 * and decides again whenever they change. Calls [onShouldRunChangedListener] when the decision
 * changes, and [onSyncPreconditionChangedListener] after every positive decision so per-object
 * conditions get applied.
 */
@Suppress("DEPRECATION") // ConnectivityManager.CONNECTIVITY_ACTION: what the monitor listens for.
class RunConditionMonitor(
    private val context: Context,
    private val onShouldRunChangedListener: OnShouldRunChangedListener?,
    private val onSyncPreconditionChangedListener: OnSyncPreconditionChangedListener?,
) {
    fun interface OnShouldRunChangedListener {
        fun onShouldRunDecisionChanged(shouldRun: Boolean)
    }

    fun interface OnSyncPreconditionChangedListener {
        fun onSyncPreconditionChanged(runConditionMonitor: RunConditionMonitor)
    }

    lateinit var preferences: SharedPreferences

    private val enableVerboseLog: Boolean
    private val checks: RunConditionChecks
    private val res = context.resources

    private var syncStatusObserverHandle: Any? = null
    private val syncStatusObserver = SyncStatusObserver {
        Handler(Looper.getMainLooper()).post { updateShouldRunDecision() }
    }
    private var syncTriggerReceiver: SyncTriggerReceiver? = null
    private var updateShouldRunDecisionReceiver: UpdateShouldRunDecisionReceiver? = null

    /** The explanation of the last decision, shown to the user. */
    var runDecisionExplanation: String = ""
        private set

    /**
     * Only relevant if the user turned Syncthing on by time schedule for a specific amount of time
     * periodically: true within a "SyncthingNative should run" time frame. Starts false because we
     * check if the last sync was more than one sleep interval ago.
     */
    private var timeConditionMatch = false

    /** Avoids re-scheduling the stop if run conditions change while already running. */
    private var runAllowedStopScheduled = false

    private var triggeredSyncDurationS = DEFAULT_TRIGGERED_SECONDS
    private var triggeredSyncSleepIntervalS = DEFAULT_TRIGGERED_SECONDS

    /** The result of the last call to [decideShouldRun]. */
    private var lastDeterminedShouldRun = false

    init {
        preferences = context.appDependencies().preferences()
        enableVerboseLog = AppPrefs.getPrefVerboseLog(preferences)
        checks = RunConditionChecks(context, preferences, ::logV)
        logV("Created new instance")
        registerReceivers()

        if (!Constants.isRunningOnEmulator()) {
            triggeredSyncSleepIntervalS =
                minutesPref(Constants.PREF_SLEEP_INTERVAL_MINUTES, "60") * SECONDS_PER_MINUTE
        }
        var lastSyncTimeSinceBootMillisecs = preferences.getLong(Constants.PREF_LAST_RUN_TIME, 0)
        val elapsedRealtime = SystemClock.elapsedRealtime()
        // After a reboot, the last run time refers to the previous boot and may be larger than
        // elapsedRealtime: set it one sleep interval in the past so the time condition is met.
        if (lastSyncTimeSinceBootMillisecs > elapsedRealtime) {
            preferences.edit {
                putLong(
                    Constants.PREF_LAST_RUN_TIME,
                    (-triggeredSyncSleepIntervalS * MILLIS_PER_SECOND).toLong(),
                )
            }
            lastSyncTimeSinceBootMillisecs = 0
        }

        // Initially determine if syncthing should run under current circumstances.
        updateShouldRunDecision()

        // Initially schedule the SyncTrigger job.
        val elapsedSecondsSinceLastSync =
            (elapsedRealtime - lastSyncTimeSinceBootMillisecs).toInt() / MILLIS_PER_SECOND
        Log.d(
            TAG,
            "JobPrepare: mTimeConditionMatch=$timeConditionMatch, elapsedRealtime=$elapsedRealtime" +
                ", lastSyncTimeSinceBootMillisecs=$lastSyncTimeSinceBootMillisecs" +
                ", elapsedSecondsSinceLastSync=$elapsedSecondsSinceLastSync",
        )
        // A negative wait cannot happen: updateShouldRunDecision() then set the time condition.
        JobUtils.scheduleSyncTriggerServiceJob(
            context,
            if (timeConditionMatch) triggeredSyncDurationS
            else triggeredSyncSleepIntervalS - elapsedSecondsSinceLastSync,
            !timeConditionMatch,
        )
    }

    private fun registerReceivers() {
        ReceiverManager.registerReceiver(
            context,
            DecideAgainReceiver(ConnectivityManager.CONNECTIVITY_ACTION),
            IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION),
        )
        ReceiverManager.registerReceiver(
            context,
            BatteryReceiver(),
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            },
        )
        ReceiverManager.registerReceiver(
            context,
            DecideAgainReceiver(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
        )
        // Monitors android's "AutoSync" quick toggle.
        syncStatusObserverHandle =
            ContentResolver.addStatusChangeListener(
                ContentResolver.SYNC_OBSERVER_TYPE_SETTINGS,
                syncStatusObserver,
            )
        val localBroadcastManager = LocalBroadcastManager.getInstance(context)
        syncTriggerReceiver =
            SyncTriggerReceiver().also {
                localBroadcastManager.registerReceiver(it, IntentFilter(ACTION_SYNC_TRIGGER_FIRED))
            }
        updateShouldRunDecisionReceiver =
            UpdateShouldRunDecisionReceiver().also {
                localBroadcastManager.registerReceiver(
                    it,
                    IntentFilter(ACTION_UPDATE_SHOULDRUN_DECISION),
                )
            }
    }

    fun shutdown() {
        logV("Shutting down")
        JobUtils.cancelAllScheduledJobs(context)
        syncStatusObserverHandle?.let { ContentResolver.removeStatusChangeListener(it) }
        syncStatusObserverHandle = null
        val localBroadcastManager = LocalBroadcastManager.getInstance(context)
        syncTriggerReceiver?.let(localBroadcastManager::unregisterReceiver)
        syncTriggerReceiver = null
        updateShouldRunDecisionReceiver?.let(localBroadcastManager::unregisterReceiver)
        updateShouldRunDecisionReceiver = null
        ReceiverManager.unregisterAllReceivers(context)
    }

    /** Decides again when it receives [action]. */
    private inner class DecideAgainReceiver(private val action: String) : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (action == intent.action) {
                updateShouldRunDecision()
            }
        }
    }

    private inner class BatteryReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (
                Intent.ACTION_POWER_CONNECTED == intent.action ||
                    Intent.ACTION_POWER_DISCONNECTED == intent.action
            ) {
                SystemClock.sleep(BATTERY_SETTLE_MILLIS)
                updateShouldRunDecision()
            }
        }
    }

    private inner class SyncTriggerReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            runAllowedStopScheduled = false
            val beginActiveTimeWindow =
                intent.getBooleanExtra(EXTRA_BEGIN_ACTIVE_TIME_WINDOW, false)
            logV(
                "SyncTriggerReceiver: onReceive, extraBeginActiveTimeWindow=$beginActiveTimeWindow"
            )
            if (!preferences.getBoolean(Constants.PREF_RUN_ON_TIME_SCHEDULE, false)) {
                // The feature is off. Reschedule to see if the user turned it on in the meantime.
                timeConditionMatch = false
                JobUtils.cancelAllScheduledJobs(context)
                JobUtils.scheduleSyncTriggerServiceJob(context, triggeredSyncSleepIntervalS, true)
                return
            }
            if (beginActiveTimeWindow) {
                // Start SyncthingNative right away, for the sync duration.
                timeConditionMatch = true
                JobUtils.cancelAllScheduledJobs(context)
                JobUtils.scheduleSyncTriggerServiceJob(context, triggeredSyncDurationS, false)
                runAllowedStopScheduled = true
            } else {
                endActiveTimeWindow()
            }
            updateShouldRunDecision()
            // Reschedule. Outside a run window, open the next one after the sleep interval. Inside
            // one, the stop after the sync duration should be scheduled by
            // updateShouldRunDecision(),
            // but might not always be: schedule a backup stop without cancelling other jobs.
            if (!runAllowedStopScheduled && !lastDeterminedShouldRun) {
                JobUtils.cancelAllScheduledJobs(context)
                JobUtils.scheduleSyncTriggerServiceJob(context, triggeredSyncSleepIntervalS, true)
            } else {
                JobUtils.scheduleSyncTriggerServiceJob(context, triggeredSyncDurationS, false)
            }
        }
    }

    /**
     * Toggles the time condition off, as the schedule asks. If Syncthing runs and the last run was
     * more than a sleep interval ago, the time condition would switch on again right away; a fake
     * last run time slightly less than a sleep interval ago prevents that. If Syncthing really is
     * stopped (which it should) the wrong time gets corrected immediately.
     */
    private fun endActiveTimeWindow() {
        timeConditionMatch = false
        val lastRunTimeMillis = preferences.getLong(Constants.PREF_LAST_RUN_TIME, 0)
        val sleepMillis = triggeredSyncSleepIntervalS * MILLIS_PER_SECOND
        if (
            lastDeterminedShouldRun &&
                SystemClock.elapsedRealtime() - lastRunTimeMillis > sleepMillis
        ) {
            preferences.edit {
                putLong(
                    Constants.PREF_LAST_RUN_TIME,
                    SystemClock.elapsedRealtime() - sleepMillis + MILLIS_PER_MINUTE,
                )
            }
        }
    }

    private inner class UpdateShouldRunDecisionReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            logV("UpdateShouldRunDecisionReceiver: onReceive")
            updateShouldRunDecision()
        }
    }

    /** Decides again after preconditions changed, and tells the listeners. */
    fun updateShouldRunDecision() {
        if (!Constants.isRunningOnEmulator()) {
            triggeredSyncDurationS =
                minutesPref(Constants.PREF_SYNC_DURATION_MINUTES, "5") * SECONDS_PER_MINUTE
            triggeredSyncSleepIntervalS =
                minutesPref(Constants.PREF_SLEEP_INTERVAL_MINUTES, "60") * SECONDS_PER_MINUTE
        }
        val newShouldRun = decideShouldRun()
        if (newShouldRun) {
            // SyncthingService evaluates the per-object run conditions and pauses or resumes
            // folders and devices; also when Syncthing should just keep running.
            onSyncPreconditionChangedListener?.onSyncPreconditionChanged(this)
        }
        if (newShouldRun == lastDeterminedShouldRun) {
            return
        }
        // SyncthingService transitions INIT/DISABLED => STARTING => ACTIVE, or ACTIVE => DISABLED.
        if (onShouldRunChangedListener != null) {
            onShouldRunChangedListener.onShouldRunDecisionChanged(newShouldRun)
            lastDeterminedShouldRun = newShouldRun
        }
        if (
            newShouldRun &&
                !runAllowedStopScheduled &&
                preferences.getBoolean(Constants.PREF_RUN_ON_TIME_SCHEDULE, false) &&
                preferences.getInt(
                    Constants.PREF_BTNSTATE_FORCE_START_STOP,
                    Constants.BTNSTATE_NO_FORCE_START_STOP,
                ) == Constants.BTNSTATE_NO_FORCE_START_STOP
        ) {
            JobUtils.cancelAllScheduledJobs(context)
            JobUtils.scheduleSyncTriggerServiceJob(context, triggeredSyncDurationS, false)
            runAllowedStopScheduled = true
        }
        preferences.edit { putLong(Constants.PREF_LAST_RUN_TIME, SystemClock.elapsedRealtime()) }
    }

    /** Whether a folder's or device's own sync conditions are met. */
    fun checkObjectSyncConditions(objectPrefixAndId: String): Boolean =
        checks.checkObjectSyncConditions(objectPrefixAndId)

    /** Whether Syncthing should currently run; sets [runDecisionExplanation]. */
    private fun decideShouldRun(): Boolean {
        runDecisionExplanation = ""
        forcedDecision()?.let {
            return it
        }
        if (outsideTimeWindow()) {
            return false
        }
        val powerSource = checks.syncOnPowerSource(Constants.PREF_POWER_SOURCE)
        if (!powerSource.conditionMet) {
            logV("checkConditionSyncOnPowerSource: " + powerSource.explanation)
            runDecisionExplanation = powerSource.explanation
            return false
        }
        if (
            preferences.getBoolean(Constants.PREF_RESPECT_BATTERY_SAVING, true) &&
                checks.deviceState.isPowerSaving()
        ) {
            logV("decideShouldRun: prefRespectPowerSaving && isPowerSaving")
            runDecisionExplanation = res.getString(R.string.reason_not_while_power_saving)
            return false
        }
        // Android global AutoSync setting.
        if (
            preferences.getBoolean(Constants.PREF_RESPECT_MASTER_SYNC, false) &&
                !ContentResolver.getMasterSyncAutomatically()
        ) {
            logV("decideShouldRun: prefRespectMasterSync && !getMasterSyncAutomatically")
            runDecisionExplanation =
                res.getString(R.string.reason_not_while_auto_sync_data_disabled)
            return false
        }
        if (networkAllowsRun()) {
            return true
        }
        if (
            preferences.getBoolean(Constants.PREF_RUN_IN_FLIGHT_MODE, false) &&
                checks.deviceState.isFlightMode()
        ) {
            logV("decideShouldRun: prefRunInFlightMode && isFlightMode")
            runDecisionExplanation += "\n" + res.getString(R.string.reason_on_flight_mode)
            return true
        }
        // None of the run conditions matched.
        logV("decideShouldRun: return false")
        return false
    }

    /** The decision forced by the start/stop buttons, or null if they follow the conditions. */
    private fun forcedDecision(): Boolean? =
        when (
            preferences.getInt(
                Constants.PREF_BTNSTATE_FORCE_START_STOP,
                Constants.BTNSTATE_NO_FORCE_START_STOP,
            )
        ) {
            Constants.BTNSTATE_FORCE_START -> {
                logV("decideShouldRun: PREF_BTNSTATE_FORCE_START")
                runDecisionExplanation = res.getString(R.string.reason_force_start)
                true
            }
            Constants.BTNSTATE_FORCE_STOP -> {
                logV("decideShouldRun: PREF_BTNSTATE_FORCE_STOP")
                runDecisionExplanation = res.getString(R.string.reason_force_stop)
                false
            }
            else -> null
        }

    /**
     * PREF_RUN_ON_TIME_SCHEDULE: whether we are outside a "SyncthingNative should run" time frame;
     * sets the explanation if so. The time condition switches on once the last run was more than a
     * sleep interval ago.
     */
    private fun outsideTimeWindow(): Boolean {
        val sinceLastRun =
            SystemClock.elapsedRealtime() - preferences.getLong(Constants.PREF_LAST_RUN_TIME, 0)
        if (
            sinceLastRun >
                minutesPref(Constants.PREF_SLEEP_INTERVAL_MINUTES, "60") * MILLIS_PER_MINUTE
        ) {
            timeConditionMatch = true
        }
        if (
            !preferences.getBoolean(Constants.PREF_RUN_ON_TIME_SCHEDULE, false) ||
                timeConditionMatch
        ) {
            return false
        }
        logV("decideShouldRun: PREF_RUN_ON_TIME_SCHEDULE && !mTimeConditionMatch")
        val minutes = sinceLastRun.toInt() / MILLIS_PER_MINUTE
        val minutesText =
            if (minutes == 0) res.getString(R.string.reason_not_within_time_frame_0_min)
            else
                res.getQuantityString(R.plurals.reason_not_within_time_frame_minutes, minutes)
                    .format(Locale.getDefault(), minutes)
        runDecisionExplanation =
            res.getString(R.string.reason_not_within_time_frame_2)
                .format(Locale.getDefault(), minutesText)
        return true
    }

    /**
     * Mobile data (and roaming), else wifi (metered, whitelisted): whether one allows running.
     * Every condition checked adds its line to the explanation.
     */
    private fun networkAllowsRun(): Boolean {
        val mobileData = checks.syncOnMobileData(Constants.PREF_RUN_ON_MOBILE_DATA)
        runDecisionExplanation += mobileData.explanation
        if (mobileData.conditionMet) {
            logV("decideShouldRun: checkConditionSyncOnMobileData")
            val roaming = checks.syncOnRoaming(Constants.PREF_RUN_ON_ROAMING)
            runDecisionExplanation += roaming.explanation
            if (roaming.conditionMet) return true
        }
        val wifi = checks.syncOnWifi(Constants.PREF_RUN_ON_WIFI)
        runDecisionExplanation += wifi.explanation
        if (!wifi.conditionMet) return false
        logV("decideShouldRun: checkConditionSyncOnWifi")
        val metered = checks.syncOnMeteredWifi(Constants.PREF_RUN_ON_METERED_WIFI)
        runDecisionExplanation += metered.explanation
        if (!metered.conditionMet) return false
        val whitelisted =
            checks.syncOnWhitelistedWifi(
                Constants.PREF_USE_WIFI_SSID_WHITELIST,
                Constants.PREF_WIFI_SSID_WHITELIST,
            )
        runDecisionExplanation += whitelisted.explanation
        return whitelisted.conditionMet
    }

    private fun minutesPref(key: String, default: String): Int =
        Integer.parseInt(checkNotNull(preferences.getString(key, default)))

    private fun logV(logMessage: String) {
        if (enableVerboseLog) {
            Log.v(TAG, logMessage)
        }
    }

    companion object {
        private const val TAG = "RunConditionMonitor"

        const val ACTION_SYNC_TRIGGER_FIRED =
            ".service.RunConditionMonitor.ACTION_SYNC_TRIGGER_FIRED"
        const val ACTION_UPDATE_SHOULDRUN_DECISION =
            ".service.RunConditionMonitor.ACTION_UPDATE_SHOULDRUN_DECISION"
        const val EXTRA_BEGIN_ACTIVE_TIME_WINDOW =
            ".service.RunConditionMonitor.BEGIN_ACTIVE_TIME_WINDOW"

        private const val DEFAULT_TRIGGERED_SECONDS = 10
        private const val SECONDS_PER_MINUTE = 60
        private const val MILLIS_PER_SECOND = 1000
        private const val MILLIS_PER_MINUTE = 60 * 1000
        private const val BATTERY_SETTLE_MILLIS = 5000L
    }
}
