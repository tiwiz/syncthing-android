package com.nutomic.syncthingandroid.activities

import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.SyncthingApp
import com.nutomic.syncthingandroid.fragments.DeviceIdDialogFragment
import com.nutomic.syncthingandroid.model.Connection
import com.nutomic.syncthingandroid.onboarding.OnboardingActivity
import com.nutomic.syncthingandroid.recentchanges.RecentChangesActivity
import com.nutomic.syncthingandroid.service.AppPrefs
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.RunConditionMonitor
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.service.SyncthingServiceBinder
import com.nutomic.syncthingandroid.settings.SettingsActivity
import com.nutomic.syncthingandroid.theme.ApplicationTheme
import com.nutomic.syncthingandroid.util.ConfigRouter
import com.nutomic.syncthingandroid.util.ConfigXml
import com.nutomic.syncthingandroid.util.FileOpener
import com.nutomic.syncthingandroid.util.PermissionUtil
import com.nutomic.syncthingandroid.util.isTelevision
import com.nutomic.syncthingandroid.util.testPathEllipsis
import com.nutomic.syncthingandroid.views.DeviceRowState
import com.nutomic.syncthingandroid.views.FolderRowState
import com.nutomic.syncthingandroid.webgui.WebGuiActivity
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The main screen: the folders, devices and status tabs, the drawer, and the questions Syncthing
 * asks once it runs.
 */
class MainActivity : SyncthingActivity(), SyncthingService.OnServiceStateChangeListener {

    @Inject lateinit var preferences: SharedPreferences

    private lateinit var configRouter: ConfigRouter
    private var enableVerboseLog = false

    private var serviceState = SyncthingService.State.INIT
    private var lastIntent: Intent? = null

    /** The figures from the last system status, while Syncthing runs. */
    private var figures: SystemFigures? = null

    private var uiState by
        mutableStateOf(
            MainUiState(
                serviceState = SyncthingService.State.INIT,
                folders = emptyList(),
                devices = emptyList(),
                statusLines = emptyList(),
                forceStartStop = Constants.BTNSTATE_NO_FORCE_START_STOP,
                totalProgress = null,
                exitVisible = true,
                runningAsService = false,
                showWebGui = true,
                usageReport = null,
            )
        )

    /** Whether the drawer should be open; the Compose drawer follows it and reports back. */
    private var drawerOpen by mutableStateOf(false)
    private var requestDrawerFocus by mutableStateOf(false)

    private val backPressedCallback =
        object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Like the home button: coming back shows the lists without waiting again.
                moveTaskToBack(true)
            }
        }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Constants.PREF_BTNSTATE_FORCE_START_STOP) refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as SyncthingApp).component().inject(this)
        configRouter = ConfigRouter(this)
        enableVerboseLog = AppPrefs.getPrefVerboseLog(preferences)
        if (enableVerboseLog) {
            testPathEllipsis()
        }
        uiState =
            uiState.copy(
                showWebGui = Constants.isDebuggable(this) || !resources.configuration.isTelevision
            )

        setContent {
            ApplicationTheme {
                val drawerState = rememberDrawerState(DrawerValue.Closed)
                LaunchedEffect(drawerOpen) {
                    if (drawerOpen) drawerState.open() else drawerState.close()
                }
                LaunchedEffect(drawerState) {
                    snapshotFlow { drawerState.currentValue }
                        .collect {
                            drawerOpen = it == DrawerValue.Open
                            if (drawerOpen) requestDrawerFocus = true
                        }
                }
                BackHandler(enabled = drawerOpen) { drawerOpen = false }
                MainContent(
                    state = uiState,
                    drawerState = drawerState,
                    requestDrawerFocus = requestDrawerFocus,
                    drawerActions = drawerActions,
                    actions = actions,
                )
            }
        }

        // The user can launch this screen straight from the recent apps: start the service here.
        val serviceIntent = Intent(this, SyncthingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        lastIntent = intent
        onBackPressedDispatcher.addCallback(this, backPressedCallback)
        preferences.registerOnSharedPreferenceChangeListener(prefListener)
        startRefreshing()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        lastIntent = intent
    }

    override fun onResume() {
        // The storage permission can be revoked at runtime.
        if (!PermissionUtil.haveStoragePermission(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            super.onResume()
            return
        }
        // The metered Wi-Fi flags may have changed.
        service?.evaluateRunConditions()
        refresh()
        if (lastIntent?.action == ACTION_EXIT) {
            Log.i(TAG, "Exit app requested by notification action")
            stopService(Intent(this, SyncthingService::class.java))
            finishAndRemoveTask()
        }
        super.onResume()
    }

    override fun onDestroy() {
        super.onDestroy()
        preferences.unregisterOnSharedPreferenceChangeListener(prefListener)
        service?.unregisterOnServiceStateChangeListener(this)
    }

    override fun onServiceConnected(componentName: ComponentName, iBinder: IBinder) {
        super.onServiceConnected(componentName, iBinder)
        (iBinder as SyncthingServiceBinder).service.registerOnServiceStateChangeListener(this)
    }

    override fun onServiceStateChange(currentState: SyncthingService.State) {
        serviceState = currentState
        when (currentState) {
            SyncthingService.State.ACTIVE -> askUsageReportingIfDue()
            SyncthingService.State.ERROR -> finish()
            else -> Unit
        }
        refresh()
    }

    /**
     * Refreshes the screen while it is shown: the lists and progress every
     * [Constants.GUI_UPDATE_INTERVAL], Syncthing's own figures every
     * [Constants.REST_UPDATE_INTERVAL].
     */
    private fun startRefreshing() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
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
        }
    }

    /** Asks Syncthing for the device states and its figures; the answers refresh the screen. */
    private fun queryRestApi() {
        val restApi = api.takeIf { serviceState == SyncthingService.State.ACTIVE } ?: return
        // A cache miss queries the status of all devices.
        restApi.getRemoteDeviceStatus("")
        restApi.getSystemStatus { status ->
            val total =
                restApi.takeIf { it.isConfigLoaded }?.totalConnectionStatistic ?: Connection()
            figures = systemFigures(this, status, total)
            refresh()
        }
    }

    private fun refresh() {
        if (isFinishing) return
        val restApi = api
        val running = restApi?.takeIf { it.isConfigLoaded }
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
        uiState =
            uiState.copy(
                serviceState = serviceState,
                folders =
                    folders.map {
                        FolderItem(
                            it.id.orEmpty(),
                            it.path.orEmpty(),
                            FolderRowState.of(this, it, running?.getFolderStatus(it.id)),
                        )
                    },
                devices =
                    devices.map {
                        DeviceItem(
                            it.deviceID.orEmpty(),
                            DeviceRowState.of(
                                this,
                                it,
                                lastSeen(it.deviceID),
                                configRouter.getSharedFolders(it.deviceID),
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
                statusLines =
                    service
                        ?.let {
                            statusLines(this, serviceState, it.runDecisionExplanation, figures)
                        }
                        .orEmpty(),
                forceStartStop =
                    preferences.getInt(
                        Constants.PREF_BTNSTATE_FORCE_START_STOP,
                        Constants.BTNSTATE_NO_FORCE_START_STOP,
                    ),
                totalProgress =
                    total.takeIf { serviceState == SyncthingService.State.ACTIVE && it != -1 },
                exitVisible = !runningAsService(),
                runningAsService = runningAsService(),
            )
    }

    private fun runningAsService() =
        preferences.getBoolean(Constants.PREF_START_SERVICE_ON_BOOT, false)

    /** Asks about usage reporting once the app is a few days old and the user has not decided. */
    private fun askUsageReportingIfDue() {
        val restApi = api ?: return
        val firstStart =
            try {
                packageManager.getPackageInfo(packageName, 0).firstInstallTime
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "This should never happen", e)
                0
            }
        val delayPassed = Date().time > firstStart + USAGE_REPORTING_DIALOG_DELAY
        if (!delayPassed || !restApi.isConfigLoaded || restApi.isUsageReportingDecided) return
        Log.v(TAG, "showUsageReportingDialog triggered.")
        restApi.getUsageReport { report -> uiState = uiState.copy(usageReport = report) }
    }

    /** Stops Syncthing and leaves the app. */
    private fun doExit() {
        if (isFinishing) return
        Log.i(TAG, "Exiting app on user request")
        stopService(Intent(this, SyncthingService::class.java))
        finishAndRemoveTask()
    }

    private fun showDeviceIdDialog() {
        val deviceId = preferences.getString(Constants.PREF_LOCAL_DEVICE_ID, "").orEmpty()
        if (deviceId.isEmpty()) {
            Toast.makeText(this, R.string.could_not_access_deviceid, Toast.LENGTH_SHORT).show()
            return
        }
        val name =
            configRouter
                .getDevices(api, true)
                .firstOrNull { it.deviceID == deviceId }
                ?.displayName
                .orEmpty()
        DeviceIdDialogFragment.show(supportFragmentManager, name.trim(), deviceId, true)
    }

    /** The menu key toggles the drawer; D-pad right closes it. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            drawerOpen = !drawerOpen
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && drawerOpen) {
            drawerOpen = false
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private val drawerActions =
        object : DrawerActions {
            private fun open(intent: Intent) {
                startActivity(intent)
                drawerOpen = false
            }

            override fun onShowDeviceId() {
                showDeviceIdDialog()
                drawerOpen = false
            }

            override fun onRecentChanges() =
                open(Intent(this@MainActivity, RecentChangesActivity::class.java))

            override fun onWebGui() = open(Intent(this@MainActivity, WebGuiActivity::class.java))

            override fun onBackup() =
                open(
                    Intent(this@MainActivity, SettingsActivity::class.java)
                        .putExtra(SettingsActivity.EXTRA_START_DESTINATION, "ImportExport")
                )

            override fun onRestart() {
                startService(
                    Intent(this@MainActivity, SyncthingService::class.java)
                        .setAction(SyncthingService.ACTION_RESTART)
                )
                drawerOpen = false
            }

            override fun onSettings() =
                open(Intent(this@MainActivity, SettingsActivity::class.java))

            override fun onExit() = doExit()
        }

    private val actions =
        object : MainActions {
            /** Sends [action] for the folder through our service to the REST API. */
            private fun sendFolderAction(action: String, folderId: String) {
                startService(
                    Intent(this@MainActivity, SyncthingService::class.java)
                        .putExtra(SyncthingService.EXTRA_FOLDER_ID, folderId)
                        .setAction(action)
                )
            }

            override fun onOpenFolder(folderId: String) {
                startActivity(
                    Intent(this@MainActivity, FolderActivity::class.java)
                        .putExtra(FolderActivity.EXTRA_IS_CREATE, false)
                        .putExtra(FolderActivity.EXTRA_FOLDER_ID, folderId)
                )
            }

            override fun onOpenFolderInFiles(path: String) =
                FileOpener.openFolder(this@MainActivity, path)

            override fun onOverride(folderId: String) =
                sendFolderAction(SyncthingService.ACTION_OVERRIDE_CHANGES, folderId)

            override fun onRevert(folderId: String) =
                sendFolderAction(SyncthingService.ACTION_REVERT_LOCAL_CHANGES, folderId)

            override fun onOpenDevice(deviceId: String) {
                startActivity(
                    Intent(this@MainActivity, DeviceActivity::class.java)
                        .putExtra(DeviceActivity.EXTRA_IS_CREATE, false)
                        .putExtra(DeviceActivity.EXTRA_DEVICE_ID, deviceId)
                )
            }

            override fun onAddFolder() {
                startActivity(FolderActivity.createIntent(this@MainActivity))
            }

            override fun onRescanAll() {
                val restApi = api
                if (restApi == null || !restApi.isConfigLoaded) {
                    Log.e(TAG, "rescanAll skipped because Syncthing is not running.")
                    return
                }
                restApi.rescanAll()
            }

            override fun onAddDevice() {
                startActivity(DeviceActivity.createIntent(this@MainActivity))
            }

            override fun onSettings() {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }

            override fun onForceStartStop(index: Int) {
                // The index is one of the Constants.BTNSTATE_* values.
                preferences.edit { putInt(Constants.PREF_BTNSTATE_FORCE_START_STOP, index) }
                LocalBroadcastManager.getInstance(this@MainActivity)
                    .sendBroadcast(Intent(RunConditionMonitor.ACTION_UPDATE_SHOULDRUN_DECISION))
                refresh()
            }

            override fun onExit() = doExit()

            override fun onUsageReporting(answer: UsageAnswer) {
                uiState = uiState.copy(usageReport = null)
                val restApi = api ?: return
                when (answer) {
                    UsageAnswer.YES,
                    UsageAnswer.NO -> {
                        restApi.setUsageReporting(answer == UsageAnswer.YES)
                        restApi.sendConfig()
                    }
                    UsageAnswer.WEBSITE ->
                        startActivity(
                            Intent(this@MainActivity, WebViewActivity::class.java)
                                .putExtra(
                                    WebViewActivity.EXTRA_WEB_URL,
                                    getString(R.string.syncthing_usage_stats_url),
                                )
                        )
                }
            }
        }

    companion object {
        private const val TAG = "MainActivity"

        /** Intent action to exit the app. */
        const val ACTION_EXIT = ".MainActivity.EXIT"

        /** How long after the first start the usage reporting question waits. */
        private val USAGE_REPORTING_DIALOG_DELAY = TimeUnit.DAYS.toMillis(3)
    }
}
