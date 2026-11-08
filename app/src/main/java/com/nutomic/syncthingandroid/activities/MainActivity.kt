package com.nutomic.syncthingandroid.activities

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.onboarding.OnboardingActivity
import com.nutomic.syncthingandroid.recentchanges.RecentChangesActivity
import com.nutomic.syncthingandroid.service.AppPrefs
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.settings.SettingsActivity
import com.nutomic.syncthingandroid.theme.ApplicationTheme
import com.nutomic.syncthingandroid.util.ConfigRouter
import com.nutomic.syncthingandroid.util.FileOpener
import com.nutomic.syncthingandroid.util.PermissionUtil
import com.nutomic.syncthingandroid.util.testPathEllipsis
import com.nutomic.syncthingandroid.webgui.WebGuiActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * The main screen: the folders, devices and status destinations, the overflow menu, and the
 * questions Syncthing asks once it runs.
 */
@AndroidEntryPoint
class MainActivity : ThemedAppCompatActivity() {

    @Inject lateinit var preferences: SharedPreferences

    private lateinit var configRouter: ConfigRouter
    private var enableVerboseLog = false

    private var lastIntent: Intent? = null

    private val viewModel: MainViewModel by viewModels()

    /** Whether the overflow menu is open; hoisted so the menu key can toggle it. */
    private var menuOpen by mutableStateOf(false)

    /** Whether the window has room for the detail pane; reported by the screen. */
    private var twoPanes by mutableStateOf(false)

    /** A detail to open once the current one, with unsaved changes, is discarded. */
    private var pendingDetail: String? = null

    private val paneCallbacks =
        PaneCallbacks(
            onClose = { saved ->
                closeDetail()
                if (saved) viewModel.refresh()
            },
            onLeave = ::closeDetail,
            onShowDeviceId = { name, deviceId ->
                viewModel.deviceIdDialog.show(DeviceIdRequest(name, deviceId, false))
            },
        )

    private val backPressedCallback =
        object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Like the home button: coming back shows the lists without waiting again.
                moveTaskToBack(true)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configRouter = ConfigRouter(this)
        enableVerboseLog = AppPrefs.getPrefVerboseLog(preferences)
        if (enableVerboseLog) {
            testPathEllipsis()
        }
        setContent {
            ApplicationTheme {
                val selection by viewModel.detail.collectAsStateWithLifecycle()
                val detail = remember(selection) { selection?.let { detailOf(it, paneCallbacks) } }
                // How far a back gesture closing the pane has gone, 0 to 1.
                var backProgress by remember { mutableFloatStateOf(0f) }
                // The detail pane shrinks with the back gesture; letting go closes it.
                PredictiveBackHandler(enabled = detail != null && twoPanes) { events ->
                    try {
                        events.collect { backProgress = it.progress }
                        currentDetail()?.back()
                    } finally {
                        backProgress = 0f
                    }
                }
                BackHandler(enabled = menuOpen) { menuOpen = false }
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                MainContent(
                    state = uiState,
                    menuOpen = menuOpen,
                    onMenuOpenChange = { menuOpen = it },
                    menuActions = menuActions,
                    actions = actions,
                    detail = detail,
                    detailBackProgress = { backProgress },
                    onTwoPanesChange = { twoPanes = it },
                    deviceIdDialog = viewModel.deviceIdDialog,
                )
            }
        }

        // The user can launch this screen straight from the recent apps: start the service here.
        val serviceIntent = Intent(this, SyncthingService::class.java)
        startForegroundService(serviceIntent)
        lastIntent = intent
        onBackPressedDispatcher.addCallback(this, backPressedCallback)
        lifecycleScope.launch {
            // The lists and figures refresh while the screen is shown.
            repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.refreshWhileShown() }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewModel.events.collect { if (it == MainEvent.FINISH) finish() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        lastIntent = intent
    }

    override fun onResume() {
        // The storage permission can be revoked at runtime.
        if (!PermissionUtil.haveStoragePermission()) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            super.onResume()
            return
        }
        // The metered Wi-Fi flags may have changed.
        viewModel.evaluateRunConditions()
        viewModel.refresh()
        if (lastIntent?.action == ACTION_EXIT) {
            Log.i(TAG, "Exit app requested by notification action")
            stopService(Intent(this, SyncthingService::class.java))
            finishAndRemoveTask()
        }
        super.onResume()
    }

    /** The detail open in the pane, from [MainViewModel]'s selection. */
    private fun currentDetail(): Detail? =
        viewModel.detail.value?.let { detailOf(it, paneCallbacks) }

    /**
     * Shows the folder or device [key] in the pane; false when the window has no room for it. With
     * unsaved changes in the pane, asks to discard them first.
     */
    private fun openDetail(key: String): Boolean {
        if (!twoPanes) return false
        val current = currentDetail()
        if (current != null && current.hasChanges()) {
            pendingDetail = key
            current.askDiscard()
        } else {
            viewModel.openDetail(key)
        }
        return true
    }

    private fun closeDetail() {
        viewModel.closeDetail()
        pendingDetail?.let {
            pendingDetail = null
            openDetail(it)
        }
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
                .getDevices(viewModel.api, true)
                .firstOrNull { it.deviceID == deviceId }
                ?.displayName
                .orEmpty()
        viewModel.deviceIdDialog.show(DeviceIdRequest(name.trim(), deviceId, true))
    }

    /** The menu key toggles the overflow menu. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            menuOpen = !menuOpen
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private val menuActions =
        object : MenuActions {
            override fun onShowDeviceId() = showDeviceIdDialog()

            override fun onRecentChanges() =
                startActivity(Intent(this@MainActivity, RecentChangesActivity::class.java))

            override fun onWebGui() =
                startActivity(Intent(this@MainActivity, WebGuiActivity::class.java))

            override fun onBackup() =
                startActivity(
                    Intent(this@MainActivity, SettingsActivity::class.java)
                        .putExtra(SettingsActivity.EXTRA_START_DESTINATION, "ImportExport")
                )

            override fun onRestart() {
                startService(
                    Intent(this@MainActivity, SyncthingService::class.java)
                        .setAction(SyncthingService.ACTION_RESTART)
                )
            }

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
                if (openDetail(DetailSelection.FOLDER + folderId)) return
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
                if (openDetail(DetailSelection.DEVICE + deviceId)) return
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
                val restApi = viewModel.api
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

            override fun onForceStartStop(index: Int) = viewModel.onForceStartStop(index)

            override fun onExit() = doExit()

            override fun onUsageReporting(answer: UsageAnswer) {
                viewModel.onUsageReporting(answer)
                if (answer == UsageAnswer.WEBSITE) {
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
    }
}
