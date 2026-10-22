package com.nutomic.syncthingandroid.activities

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.nutomic.syncthingandroid.model.Connection
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.RestApi
import com.nutomic.syncthingandroid.service.RunConditionMonitor
import com.nutomic.syncthingandroid.service.ServiceSnapshot
import com.nutomic.syncthingandroid.service.SyncthingConnection
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.util.ConfigRouter
import com.nutomic.syncthingandroid.util.ConfigXml
import com.nutomic.syncthingandroid.util.isTelevision
import com.nutomic.syncthingandroid.views.DeviceRowState
import com.nutomic.syncthingandroid.views.FolderRowState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the main screen asks its activity to do once. */
internal enum class MainEvent {
    /** Syncthing crashed: the screen closes. */
    FINISH
}

/**
 * The main screen's state: the folder and device lists, the status tab and the usage reporting
 * question. Lives across configuration changes; Syncthing comes from [SyncthingConnection].
 */
@HiltViewModel
internal class MainViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val preferences: SharedPreferences,
    connection: SyncthingConnection,
) : ViewModel() {

    private val configRouter = ConfigRouter(context)

    private val state =
        MutableStateFlow(
            MainUiState(
                serviceState = SyncthingService.State.INIT,
                folders = emptyList(),
                devices = emptyList(),
                status = null,
                forceStartStop = Constants.BTNSTATE_NO_FORCE_START_STOP,
                totalProgress = null,
                runningAsService = false,
                showWebGui =
                    Constants.isDebuggable(context) ||
                        !context.resources.configuration.isTelevision,
                usageReport = null,
            )
        )
    val uiState: StateFlow<MainUiState> = state.asStateFlow()

    private val eventChannel = Channel<MainEvent>(Channel.BUFFERED)
    val events: Flow<MainEvent> = eventChannel.receiveAsFlow()

    private var snapshot = ServiceSnapshot.UNBOUND

    /** The figures from the last system status, while Syncthing runs. */
    private var figures: SystemFigures? = null

    /** The usage report was already asked for; it is asked once per screen. */
    private var usageAsked = false

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Constants.PREF_BTNSTATE_FORCE_START_STOP) refresh()
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(prefListener)
        viewModelScope.launch { connection.snapshot.collect(::onSnapshot) }
    }

    override fun onCleared() {
        preferences.unregisterOnSharedPreferenceChangeListener(prefListener)
    }

    private fun onSnapshot(new: ServiceSnapshot) {
        val stateChanged = new.state != snapshot.state
        snapshot = new
        if (stateChanged) {
            when (new.state) {
                SyncthingService.State.ACTIVE -> askUsageReportingIfDue()
                SyncthingService.State.ERROR -> eventChannel.trySend(MainEvent.FINISH)
                else -> Unit
            }
        }
        refresh()
    }

    /**
     * Refreshes the screen while the caller runs it (while shown): the lists and progress every
     * [Constants.GUI_UPDATE_INTERVAL], Syncthing's own figures every
     * [Constants.REST_UPDATE_INTERVAL].
     */
    suspend fun refreshWhileShown() {
        var sinceQuery = Long.MAX_VALUE
        while (true) {
            if (sinceQuery >= Constants.REST_UPDATE_INTERVAL) {
                queryRestApi()
                sinceQuery = 0
            }
            refresh()
            delay(Constants.GUI_UPDATE_INTERVAL)
            sinceQuery += Constants.GUI_UPDATE_INTERVAL
        }
    }

    /** Asks Syncthing for the device states and its figures; the answers refresh the screen. */
    private fun queryRestApi() {
        val restApi =
            snapshot.api.takeIf { snapshot.state == SyncthingService.State.ACTIVE } ?: return
        // A cache miss queries the status of all devices.
        restApi.getRemoteDeviceStatus("")
        restApi.getSystemStatus { status ->
            val total =
                restApi.takeIf { it.isConfigLoaded }?.totalConnectionStatistic ?: Connection()
            figures = systemFigures(context, status, total)
            refresh()
        }
    }

    /** The REST API, or null while the service is not bound or Syncthing does not run. */
    val api: RestApi?
        get() = snapshot.api

    /** Lets the service check its run conditions again, e.g. the metered Wi-Fi flags. */
    fun evaluateRunConditions() {
        snapshot.service?.evaluateRunConditions()
    }

    fun refresh() {
        val restApi = snapshot.api
        val running = restApi?.takeIf { it.isConfigLoaded }
        val serviceState = snapshot.state ?: SyncthingService.State.INIT
        val folders =
            try {
                configRouter.getFolders(restApi)
            } catch (_: ConfigXml.OpenConfigException) {
                Log.e(TAG, "Failed to parse existing config. You will need support from here ...")
                return
            }
        val devices =
            configRouter
                .getDevices(restApi, false)
                .sortedWith(compareBy { it.name.ifEmpty { it.deviceID.orEmpty() } })
        val lastSeen = { id: String? ->
            preferences.getString(Constants.PREF_CACHE_DEVICE_LASTSEEN_PREFIX + id, "").orEmpty()
        }
        val total = running?.totalSyncCompletion ?: -1
        state.update { current ->
            current.copy(
                serviceState = serviceState,
                folders =
                    folders.map {
                        FolderItem(
                            it.id.orEmpty(),
                            it.path.orEmpty(),
                            FolderRowState.of(context, it, running?.getFolderStatus(it.id)),
                        )
                    },
                devices =
                    devices.map {
                        DeviceItem(
                            it.deviceID.orEmpty(),
                            DeviceRowState.of(
                                context,
                                it,
                                lastSeen(it.deviceID),
                                // From the list above: no config read per device.
                                folders.filter { folder -> folder.getDevice(it.deviceID) != null },
                                running?.let { api ->
                                    DeviceRowState.Status(
                                        api.getRemoteDeviceStatus(it.deviceID),
                                        api.getRemoteDeviceCompletion(it.deviceID),
                                        api.getRemoteDeviceNeedBytes(it.deviceID),
                                    )
                                },
                            ),
                        )
                    },
                status =
                    snapshot.service?.let {
                        statusInfo(context, serviceState, it.runDecisionExplanation, figures)
                    },
                forceStartStop =
                    preferences.getInt(
                        Constants.PREF_BTNSTATE_FORCE_START_STOP,
                        Constants.BTNSTATE_NO_FORCE_START_STOP,
                    ),
                totalProgress =
                    total.takeIf { serviceState == SyncthingService.State.ACTIVE && it != -1 },
                runningAsService =
                    preferences.getBoolean(Constants.PREF_START_SERVICE_ON_BOOT, false),
            )
        }
    }

    /** Asks about usage reporting once the app is a few days old and the user has not decided. */
    private fun askUsageReportingIfDue() {
        val restApi = snapshot.api ?: return
        if (usageAsked) return
        val firstStart =
            try {
                context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "This should never happen", e)
                0
            }
        val delayPassed = Date().time > firstStart + USAGE_REPORTING_DIALOG_DELAY
        if (!delayPassed || !restApi.isConfigLoaded || restApi.isUsageReportingDecided) return
        Log.v(TAG, "showUsageReportingDialog triggered.")
        usageAsked = true
        restApi.getUsageReport { report -> state.update { it.copy(usageReport = report) } }
    }

    /** Answers the usage reporting question; [UsageAnswer.WEBSITE] is opened by the screen. */
    fun onUsageReporting(answer: UsageAnswer) {
        state.update { it.copy(usageReport = null) }
        val restApi = snapshot.api ?: return
        if (answer != UsageAnswer.WEBSITE) {
            restApi.setUsageReporting(answer == UsageAnswer.YES)
            restApi.sendConfig()
        }
    }

    /** [index] is one of the Constants.BTNSTATE_* values. */
    fun onForceStartStop(index: Int) {
        preferences.edit { putInt(Constants.PREF_BTNSTATE_FORCE_START_STOP, index) }
        LocalBroadcastManager.getInstance(context)
            .sendBroadcast(Intent(RunConditionMonitor.ACTION_UPDATE_SHOULDRUN_DECISION))
        refresh()
    }

    private companion object {
        const val TAG = "MainViewModel"

        /** How long after the first start the usage reporting question waits. */
        val USAGE_REPORTING_DIALOG_DELAY = TimeUnit.DAYS.toMillis(3)
    }
}
