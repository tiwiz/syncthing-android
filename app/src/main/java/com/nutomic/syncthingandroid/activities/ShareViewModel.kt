package com.nutomic.syncthingandroid.activities

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.content.edit
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.service.AppPrefs
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.RunConditionMonitor
import com.nutomic.syncthingandroid.service.ServiceSnapshot
import com.nutomic.syncthingandroid.service.SyncthingConnection
import com.nutomic.syncthingandroid.util.ConfigRouter
import com.nutomic.syncthingandroid.util.ConfigXml
import com.nutomic.syncthingandroid.util.formatPath
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the share screen shows. */
internal data class ShareUiState(
    val names: String = "",
    val multipleFiles: Boolean = false,
    val folders: List<Folder> = emptyList(),
    val selectedFolder: Folder? = null,
    val subDirectory: String = "",
    val copying: Boolean = false,
)

/**
 * The share screen's state: the shared files and the names they get, the folders to choose from and
 * a copy in flight. The typed names survive process death; the folders are loaded again whenever
 * Syncthing's state changes.
 */
@HiltViewModel
internal class ShareViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val preferences: SharedPreferences,
    private val savedState: SavedStateHandle,
    connection: SyncthingConnection,
) : ViewModel() {

    private val configRouter = ConfigRouter(context)
    private val enableVerboseLog = AppPrefs.getPrefVerboseLog(preferences)

    /** The shared files and the names they get, in the order they came. */
    private val files = LinkedHashMap<Uri, String>()

    private val state = MutableStateFlow(ShareUiState())
    val uiState: StateFlow<ShareUiState> = state.asStateFlow()

    private val finishChannel = Channel<Unit>(Channel.BUFFERED)

    /** The screen is done: it closes. */
    val finishes: Flow<Unit> = finishChannel.receiveAsFlow()

    private var snapshot = ServiceSnapshot.UNBOUND

    init {
        viewModelScope.launch {
            connection.snapshot.collect { new ->
                val stateChanged = new.state != snapshot.state
                snapshot = new
                if (new.state != null && stateChanged) {
                    loadFolders()
                }
            }
        }
    }

    /** Takes the shared [uris], once. */
    fun start(uris: List<Uri>) {
        if (files.isNotEmpty()) return
        uris.associateWithTo(files) { displayNameFor(context, it) }
        val names = savedState[NAMES] ?: files.values.joinToString("\n")
        state.update { it.copy(names = names, multipleFiles = files.size > 1) }
    }

    fun onNamesChange(names: String) {
        savedState[NAMES] = names
        state.update { it.copy(names = names) }
    }

    fun selectFolder(folder: Folder?) {
        val subDirectory =
            folder
                ?.let {
                    preferences.getString(ShareActivity.PREF_FOLDER_SAVED_SUBDIRECTORY + it.id, "")
                }
                .orEmpty()
        state.update { it.copy(selectedFolder = folder, subDirectory = subDirectory) }
    }

    /** A directory from the folder picker; only the part below the folder is shown and kept. */
    fun onDirectoryPicked(directory: String) {
        val folder = state.value.selectedFolder ?: return
        val subDirectory = directory.replace(formatPath(checkNotNull(folder.path)), "")
        preferences.edit {
            putString(ShareActivity.PREF_FOLDER_SAVED_SUBDIRECTORY + folder.id, subDirectory)
        }
        state.update { it.copy(subDirectory = subDirectory) }
    }

    /** Remembers the chosen folder for the next share. */
    fun rememberSelection() {
        state.value.selectedFolder?.let {
            preferences.edit {
                putString(ShareActivity.PREF_PREVIOUSLY_SELECTED_SYNCTHING_FOLDER, it.id)
            }
        }
    }

    fun save() {
        if (files.size == 1) {
            files[files.keys.single()] = state.value.names
        }
        val folder = state.value.selectedFolder
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
        val directory = File(path, state.value.subDirectory)
        state.update { it.copy(copying = true) }
        viewModelScope.launch {
            val result = copySharedFiles(context, files, directory, allowOverwrite)
            state.update { it.copy(copying = false) }
            onCopied(result, folder)
        }
    }

    private fun loadFolders() {
        val loaded =
            try {
                configRouter.getFolders(snapshot.api)
            } catch (_: ConfigXml.OpenConfigException) {
                Toast.makeText(context, R.string.complete_welcome_wizard_first, Toast.LENGTH_LONG)
                    .show()
                finishChannel.trySend(Unit)
                return
            }
        state.update { it.copy(folders = loaded) }
        val savedFolderId =
            preferences.getString(ShareActivity.PREF_PREVIOUSLY_SELECTED_SYNCTHING_FOLDER, "")
        selectFolder(loaded.firstOrNull { it.id == savedFolderId } ?: loaded.firstOrNull())
    }

    private fun onCopied(result: CopyResult, folder: Folder) {
        if (result.isError) {
            Toast.makeText(context, R.string.copy_exception, Toast.LENGTH_SHORT).show()
            finishChannel.trySend(Unit)
            return
        }
        val message =
            if (result.ignored > 0) {
                context.resources.getQuantityString(
                    R.plurals.copy_success_partially,
                    result.copied,
                    result.copied,
                    folder.label,
                    result.ignored,
                )
            } else {
                context.resources.getQuantityString(
                    R.plurals.copy_success,
                    result.copied,
                    result.copied,
                    folder.label,
                )
            }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        if (preferences.getBoolean(Constants.PREF_RUN_ON_TIME_SCHEDULE, false)) {
            // Tell RunConditionMonitor to run SyncthingNative now, for a while.
            logV("prefRunOnTimeSchedule=true, notifying RunConditionMonitor via intent")
            LocalBroadcastManager.getInstance(context)
                .sendBroadcast(
                    Intent(RunConditionMonitor.ACTION_SYNC_TRIGGER_FIRED)
                        .putExtra(RunConditionMonitor.EXTRA_BEGIN_ACTIVE_TIME_WINDOW, true)
                )
        }
        finishChannel.trySend(Unit)
    }

    private fun logV(logMessage: String) {
        if (enableVerboseLog) {
            Log.v(TAG, logMessage)
        }
    }

    private companion object {
        const val TAG = "ShareActivity"
        const val NAMES = "names"
    }
}
