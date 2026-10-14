package com.nutomic.syncthingandroid.activities

import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.SyncthingApp
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.service.AppPrefs
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.RunConditionMonitor
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.service.SyncthingServiceBinder
import com.nutomic.syncthingandroid.theme.ApplicationTheme
import com.nutomic.syncthingandroid.util.ConfigRouter
import com.nutomic.syncthingandroid.util.ConfigXml
import com.nutomic.syncthingandroid.util.formatPath
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.launch

/** Saves files shared from other apps into a Syncthing folder. */
class ShareActivity : SyncthingActivity(), SyncthingService.OnServiceStateChangeListener {

    @Inject lateinit var preferences: SharedPreferences

    private var enableVerboseLog = false
    private lateinit var configRouter: ConfigRouter
    private var syncthingService: SyncthingService? = null

    /** The shared files and the names they get, in the order they came. */
    private val files = LinkedHashMap<Uri, String>()

    private var names by mutableStateOf("")
    private var folders by mutableStateOf<List<Folder>>(emptyList())
    private var selectedFolder by mutableStateOf<Folder?>(null)
    private var subDirectory by mutableStateOf("")
    private var copying by mutableStateOf(false)

    private val pickDirectory =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val directory = result.data?.getStringExtra(FolderPickerActivity.EXTRA_RESULT_DIRECTORY)
            val folder = selectedFolder
            if (result.resultCode == RESULT_OK && directory != null && folder != null) {
                // Only the part below the folder is shown and kept.
                subDirectory = directory.replace(formatPath(checkNotNull(folder.path)), "")
                preferences.edit {
                    putString(PREF_FOLDER_SAVED_SUBDIRECTORY + folder.id, subDirectory)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as SyncthingApp).component().inject(this)
        enableVerboseLog = AppPrefs.getPrefVerboseLog(preferences)
        configRouter = ConfigRouter(this)

        val shared = sharedUris()
        if (shared.isEmpty()) {
            Toast.makeText(this, R.string.nothing_share, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        shared.associateWithTo(files) { displayNameFor(this, it) }
        names = files.values.joinToString("\n")

        enableEdgeToEdge()
        setContent {
            ApplicationTheme {
                ShareScreen(
                    names = names,
                    multipleFiles = files.size > 1,
                    folders = folders,
                    selectedFolder = selectedFolder,
                    subDirectory = subDirectory,
                    copying = copying,
                    onNamesChange = { names = it },
                    onFolderSelect = ::selectFolder,
                    onBrowse = ::browse,
                    onSave = ::save,
                    onCancel = ::finish,
                )
            }
        }
    }

    private fun sharedUris(): List<Uri> =
        when (intent.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(
                    IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                )
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(
                        intent,
                        Intent.EXTRA_STREAM,
                        Uri::class.java,
                    )
                    .orEmpty()
            else -> emptyList()
        }

    override fun onServiceConnected(componentName: ComponentName, iBinder: IBinder) {
        super.onServiceConnected(componentName, iBinder)
        val service = (iBinder as SyncthingServiceBinder).service
        service.registerOnServiceStateChangeListener(this)
        syncthingService = service
    }

    override fun onServiceStateChange(currentState: SyncthingService.State) {
        val loaded =
            try {
                configRouter.getFolders(api)
            } catch (_: ConfigXml.OpenConfigException) {
                Toast.makeText(this, R.string.complete_welcome_wizard_first, Toast.LENGTH_LONG)
                    .show()
                finish()
                return
            }
        folders = loaded
        val savedFolderId = preferences.getString(PREF_PREVIOUSLY_SELECTED_SYNCTHING_FOLDER, "")
        selectFolder(loaded.firstOrNull { it.id == savedFolderId } ?: loaded.firstOrNull())
    }

    private fun selectFolder(folder: Folder?) {
        selectedFolder = folder
        subDirectory =
            folder
                ?.let { preferences.getString(PREF_FOLDER_SAVED_SUBDIRECTORY + it.id, "") }
                .orEmpty()
    }

    private fun browse() {
        val folder = selectedFolder ?: return
        val initialDirectory = File(folder.path, subDirectory)
        pickDirectory.launch(
            FolderPickerActivity.createIntent(
                applicationContext,
                initialDirectory.absolutePath,
                folder.path,
            )
        )
    }

    private fun save() {
        if (files.size == 1) {
            files[files.keys.single()] = names
        }
        val folder = selectedFolder
        if (folder == null) {
            Log.e(TAG, "save: folder == null")
            return
        }
        val path = folder.path
        if (path == null) {
            Log.e(TAG, "save: folder.path == null")
            return
        }
        val allowOverwrite = preferences.getBoolean(Constants.PREF_ALLOW_OVERWRITE_FILES, false)
        copying = true
        lifecycleScope.launch {
            val result =
                copySharedFiles(this@ShareActivity, files, File(path, subDirectory), allowOverwrite)
            copying = false
            onCopied(result, folder)
        }
    }

    private fun onCopied(result: CopyResult, folder: Folder) {
        if (result.isError) {
            Toast.makeText(this, R.string.copy_exception, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val message =
            if (result.ignored > 0) {
                resources.getQuantityString(
                    R.plurals.copy_success_partially,
                    result.copied,
                    result.copied,
                    folder.label,
                    result.ignored,
                )
            } else {
                resources.getQuantityString(
                    R.plurals.copy_success,
                    result.copied,
                    result.copied,
                    folder.label,
                )
            }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        if (preferences.getBoolean(Constants.PREF_RUN_ON_TIME_SCHEDULE, false)) {
            // Tell RunConditionMonitor to run SyncthingNative now, for a while.
            logV("prefRunOnTimeSchedule=true, notifying RunConditionMonitor via intent")
            LocalBroadcastManager.getInstance(this)
                .sendBroadcast(
                    Intent(RunConditionMonitor.ACTION_SYNC_TRIGGER_FIRED)
                        .putExtra(RunConditionMonitor.EXTRA_BEGIN_ACTIVE_TIME_WINDOW, true)
                )
        }
        finish()
    }

    override fun onPause() {
        super.onPause()
        selectedFolder?.let {
            preferences.edit { putString(PREF_PREVIOUSLY_SELECTED_SYNCTHING_FOLDER, it.id) }
        }
    }

    override fun onDestroy() {
        syncthingService?.unregisterOnServiceStateChangeListener(this)
        super.onDestroy()
    }

    private fun logV(logMessage: String) {
        if (enableVerboseLog) {
            Log.v(TAG, logMessage)
        }
    }

    companion object {
        private const val TAG = "ShareActivity"

        private const val PREF_PREVIOUSLY_SELECTED_SYNCTHING_FOLDER =
            "previously_selected_syncthing_folder"

        const val PREF_FOLDER_SAVED_SUBDIRECTORY = "saved_sub_directory_"
    }
}
