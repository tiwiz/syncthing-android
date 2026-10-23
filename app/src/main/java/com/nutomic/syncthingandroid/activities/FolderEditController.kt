package com.nutomic.syncthingandroid.activities

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.gson.Gson
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.model.SharedWithDevice
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.RestApi
import com.nutomic.syncthingandroid.service.RunConditionMonitor
import com.nutomic.syncthingandroid.util.ConfigRouter
import com.nutomic.syncthingandroid.util.FileUtils
import com.nutomic.syncthingandroid.util.SafUtils
import com.nutomic.syncthingandroid.util.formatPath
import com.nutomic.syncthingandroid.util.nativeBinaryCanWriteToPath
import com.nutomic.syncthingandroid.views.FolderRowState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The screens a folder screen opens for a result. */
internal enum class FolderRequest {
    /** The system directory picker (SAF). */
    TREE,
    /** The built-in directory picker, when SAF is missing or for advanced paths. */
    DIRECTORY,
    FOLDER_TYPE,
    PULL_ORDER,
    VERSIONING,
    ADD_DEVICE,
}

/** Where a folder screen lives: its own activity, or a pane of the main screen. */
internal interface FolderEditHost {
    /** The REST API, or null while Syncthing does not run. */
    val restApi: RestApi?

    /** Lives as long as the screen; saving a new folder runs in it. */
    val scope: CoroutineScope

    /** Opens [intent] for [request]; the result goes to [FolderEditController.onResult]. */
    fun launch(request: FolderRequest, intent: Intent)

    fun open(intent: Intent)

    /** Closes the screen; [saved] tells the caller whether the folder changed. */
    fun close(saved: Boolean)

    /** Goes back as if the screen had nothing to ask. */
    fun leave()
}

/** What a folder screen is opened for. */
internal data class FolderEditArgs(
    val create: Boolean,
    val folderId: String?,
    val label: String? = null,
    val receiveEncrypted: Boolean = false,
    /** A device that offers the folder: the new folder is shared with it right away. */
    val offeringDeviceId: String? = null,
) {
    companion object {
        fun of(intent: Intent) =
            FolderEditArgs(
                create = intent.getBooleanExtra(FolderActivity.EXTRA_IS_CREATE, false),
                folderId = intent.getStringExtra(FolderActivity.EXTRA_FOLDER_ID),
                label = intent.getStringExtra(FolderActivity.EXTRA_FOLDER_LABEL),
                receiveEncrypted =
                    intent.getBooleanExtra(FolderActivity.EXTRA_RECEIVE_ENCRYPTED, false),
                offeringDeviceId = intent.getStringExtra(FolderActivity.EXTRA_DEVICE_ID),
            )
    }
}

/**
 * A folder screen's state and what its user can do: loads the folder or creates one, keeps the
 * form, checks and saves it. The [host] does what needs an activity.
 */
internal class FolderEditController
private constructor(
    private val context: Context,
    private val preferences: SharedPreferences,
    private val configRouter: ConfigRouter,
    private val host: FolderEditHost,
    private val createMode: Boolean,
    /** The folder as loaded or created; the form's values are applied to it when saving. */
    private val folder: Folder,
    /** The SAF tree the user picked for a new folder, with write access. */
    private var folderUri: Uri?,
) {
    var form by mutableStateOf<FolderForm?>(null)
        private set

    var info by mutableStateOf(FolderScreenInfo(createMode, false, null, false))
        private set

    var dialog by mutableStateOf(FolderDialog.NONE)
        private set

    /** Keeps what [create] needs to restore the screen. */
    fun saveState(outState: Bundle) {
        outState.putString(STATE_FOLDER, Gson().toJson(folder))
        outState.putString(STATE_FORM, Gson().toJson(form))
        outState.putString(STATE_DIALOG, dialog.name)
        outState.putString(STATE_FOLDER_URI, folderUri?.toString())
    }

    /** Shows the saved folder's state as Syncthing reports it; nothing for a new folder. */
    fun refreshStatus() {
        if (createMode) return
        val restApi = host.restApi
        val saved = configRouter.getFolders(restApi).firstOrNull { it.id == folder.id } ?: return
        val running = restApi?.takeIf { it.isConfigLoaded }
        val row = FolderRowState.of(context, saved, running?.getFolderStatus(saved.id))
        info = info.copy(status = EditStatus(row.tone, row.state, row.progress))
    }

    /** A screen opened through [FolderEditHost.launch] returned [result]. */
    fun onResult(request: FolderRequest, result: ActivityResult) {
        if (result.resultCode != Activity.RESULT_OK) return
        val data = result.data
        when (request) {
            FolderRequest.TREE -> data?.data?.let(::onTreePicked)
            FolderRequest.DIRECTORY ->
                data?.getStringExtra(FolderPickerActivity.EXTRA_RESULT_DIRECTORY)?.let {
                    setPath(FileUtils.cutTrailingSlash(it))
                }
            FolderRequest.FOLDER_TYPE ->
                data
                    ?.getStringExtra(FolderTypeDialogActivity.EXTRA_RESULT_FOLDER_TYPE)
                    ?.let(::onFolderTypeChosen)
            FolderRequest.PULL_ORDER -> {
                val order = data?.getStringExtra(PullOrderDialogActivity.EXTRA_RESULT_PULL_ORDER)
                form = form?.copy(order = order, changed = true)
            }
            FolderRequest.VERSIONING ->
                data?.extras?.let { extras ->
                    val values = extras.keySet().associateWith { extras.getString(it).orEmpty() }
                    form = form?.withVersioning(values)
                }
            FolderRequest.ADD_DEVICE -> loadDevices()
        }
    }

    private fun customSyncConditionsPref(folderId: String?) =
        Constants.dynPrefObjectCustomSyncConditions(Constants.PREF_OBJECT_PREFIX_FOLDER + folderId)

    /**
     * Whether Syncthing may write to [path]: read-only folders can only send, so their type is
     * forced to send only.
     */
    private fun checkWriteAccess(path: String) {
        if (path.isEmpty()) {
            info = info.copy(canWriteToPath = null)
            return
        }
        val canWrite = nativeBinaryCanWriteToPath(context, path)
        info = info.copy(canWriteToPath = canWrite)
        if (!canWrite) {
            form = form?.copy(type = Constants.FOLDER_TYPE_SEND_ONLY)
        }
    }

    private fun setPath(path: String) {
        Log.v(TAG, "Got directory path '$path'")
        form = form?.copy(path = path, changed = true)
        checkWriteAccess(path)
    }

    /** A directory picked through SAF; its path, unix style ("/storage/0000-0000/DCIM"). */
    private fun onTreePicked(uri: Uri) {
        folderUri = uri
        val targetPath = SafUtils.getAbsolutePathFromSAFUri(context, uri)?.let(::formatPath)
        if (targetPath.isNullOrEmpty() || targetPath == File.separator) {
            folderUri = null
            form = form?.copy(path = "")
            checkWriteAccess("")
            // Suggest a folder on internal or external storage.
            toast(R.string.toast_invalid_folder_selected)
            return
        }
        setPath(FileUtils.cutTrailingSlash(targetPath))
    }

    private fun onFolderTypeChosen(type: String) {
        if (!createMode && type == Constants.FOLDER_TYPE_RECEIVE_ENCRYPTED) {
            // Syncthing refuses it too: posting such a config fails with HTTP 500.
            toast(R.string.folder_type_switch_to_receive_encrypted_not_allowed)
            return
        }
        form = form?.copy(type = type, changed = true)
    }

    private fun loadDevices() {
        val current = form ?: return
        val kept = current.shares.associateBy { it.deviceId }
        form =
            current.copy(
                shares =
                    FolderForm.sharesOf(folder, configRouter.getDevices(host.restApi, false)).map {
                        kept[it.deviceId] ?: it
                    }
            )
    }

    private fun toast(text: Int) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    private fun save(current: FolderForm) {
        val error =
            when {
                current.id.isEmpty() -> R.string.folder_id_required
                current.label.trim().isEmpty() -> R.string.folder_label_required
                current.path.isEmpty() -> R.string.folder_path_required
                else -> null
            }
        if (error != null) {
            toast(error)
            return
        }
        val edited = current.applyTo(folder)
        val folderId = checkNotNull(edited.id)
        preferences.edit {
            putBoolean(Constants.dynPrefObjectFolderRunScript(folderId), current.runScript)
        }
        if (createMode) {
            Log.v(TAG, "onSave: Adding folder with ID = '$folderId'")
            info = info.copy(saving = true)
            host.scope.launch {
                addFolderWithStruct(context, folderUri, current.path) {
                    configRouter.addFolder(host.restApi, edited)
                }
                // Sync right after adding a folder.
                LocalBroadcastManager.getInstance(context.applicationContext)
                    .sendBroadcast(
                        Intent(RunConditionMonitor.ACTION_SYNC_TRIGGER_FIRED)
                            .putExtra(RunConditionMonitor.EXTRA_BEGIN_ACTIVE_TIME_WINDOW, true)
                    )
                info = info.copy(saving = false)
                host.close(saved = true)
            }
            return
        }
        if (!current.changed) {
            // Nothing to save.
            host.close(saved = false)
            return
        }
        Log.v(TAG, "onSave: Updating folder with ID = '$folderId'")
        preferences.edit {
            putBoolean(customSyncConditionsPref(folderId), current.customSyncConditions)
        }
        val restApi = host.restApi
        if (current.ignoreListChanged) {
            configRouter.postFolderIgnoreList(restApi, edited, current.ignoreLines())
        }
        configRouter.updateFolder(restApi, edited)
        host.close(saved = true)
    }

    private fun remove() {
        val folderId = checkNotNull(folder.id)
        configRouter.removeFolder(host.restApi, folderId)
        if (folderId == Constants.SYNCTHING_CAMERA_FOLDER_ID) {
            // Withdraw the consent to Syncthing Camera.
            preferences.edit { putBoolean(Constants.PREF_ENABLE_SYNCTHING_CAMERA, false) }
        }
        form = form?.copy(changed = false)
        host.close(saved = false)
    }

    /** The SAF directory picker, starting in the app's media folder or the storage root. */
    private fun pickPath() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        val initialUri =
            listOfNotNull(
                    FileUtils.getExternalFilesDirUri(
                        context,
                        FileUtils.ExternalStorageDirType.INT_MEDIA,
                    ),
                    FileUtils.getInternalStorageRootUri(),
                )
                .firstOrNull { SafUtils.directoryUriExists(context, it) }
        if (initialUri != null) {
            Log.v(TAG, "onPathViewClick: INITIAL_URI = $initialUri")
            intent.putExtra("android.provider.extra.INITIAL_URI", initialUri)
        }
        intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true)
        intent.putExtra("android.content.extra.SHOW_ADVANCED", true)
        try {
            host.launch(FolderRequest.TREE, intent)
        } catch (e: ActivityNotFoundException) {
            Log.e(
                TAG,
                "onPathViewClick exception, falling back to built-in FolderPickerActivity.",
                e,
            )
            host.launch(
                FolderRequest.DIRECTORY,
                FolderPickerActivity.createIntent(context, form?.path, null),
            )
        }
    }

    val actions: FolderScreenActions =
        object : FolderScreenActions {
            override fun onFormChange(form: FolderForm) {
                this@FolderEditController.form = form
            }

            override fun onSave() {
                if (!info.saving) form?.let(::save)
            }

            override fun onBack() {
                when {
                    info.saving -> Unit
                    form?.changed == true -> dialog = FolderDialog.DISCARD
                    else -> host.leave()
                }
            }

            override fun onPickPath() = pickPath()

            override fun onPickAdvancedDirectory() {
                host.launch(
                    FolderRequest.DIRECTORY,
                    FolderPickerActivity.createIntent(context, form?.path, null),
                )
            }

            override fun onFolderType() {
                val current = form ?: return
                when {
                    current.path.isEmpty() -> toast(R.string.folder_path_required)
                    // Read-only folders can only send; the screen explains why.
                    info.canWriteToPath != true -> toast(R.string.folder_path_readonly)
                    else ->
                        host.launch(
                            FolderRequest.FOLDER_TYPE,
                            Intent(context, FolderTypeDialogActivity::class.java)
                                .putExtra(FolderTypeDialogActivity.EXTRA_FOLDER_TYPE, current.type),
                        )
                }
            }

            override fun onPullOrder() {
                host.launch(
                    FolderRequest.PULL_ORDER,
                    Intent(context, PullOrderDialogActivity::class.java)
                        .putExtra(PullOrderDialogActivity.EXTRA_PULL_ORDER, form?.order),
                )
            }

            override fun onVersioning() {
                val current = form ?: return
                val intent = Intent(context, VersioningDialogActivity::class.java)
                current.versioningParams.forEach { (key, value) -> intent.putExtra(key, value) }
                intent.putExtra("type", current.versioningType.ifEmpty { "none" })
                host.launch(FolderRequest.VERSIONING, intent)
            }

            override fun onOpenSyncConditions() {
                host.open(
                    SyncConditionsActivity.createIntent(
                        context,
                        Constants.PREF_OBJECT_PREFIX_FOLDER + folder.id,
                        folder.label,
                    )
                )
            }

            override fun onAddDevice() {
                host.launch(FolderRequest.ADD_DEVICE, DeviceActivity.createIntent(context))
            }

            override fun onDialog(dialog: FolderDialog) {
                this@FolderEditController.dialog = dialog
            }

            override fun onConfirmDialog(dialog: FolderDialog) {
                this@FolderEditController.dialog = FolderDialog.NONE
                when (dialog) {
                    FolderDialog.DELETE -> remove()
                    FolderDialog.DISCARD -> host.close(saved = false)
                    FolderDialog.NONE -> Unit
                }
            }
        }

    companion object {
        private const val TAG = "FolderEditController"
        private const val TRASHCAN_CLEANOUT_DAYS = 14
        private const val STATE_FOLDER = "folder"
        private const val STATE_FORM = "form"
        private const val STATE_DIALOG = "dialog"
        private const val STATE_FOLDER_URI = "folderUri"

        /**
         * The controller of the folder [args] asks for, restored from [savedState] if any; null
         * when the folder to edit is gone.
         */
        @Suppress("LongParameterList") // What a folder screen needs, from its host.
        fun create(
            context: Context,
            preferences: SharedPreferences,
            configRouter: ConfigRouter,
            host: FolderEditHost,
            args: FolderEditArgs,
            savedState: Bundle?,
        ): FolderEditController? {
            val restoredForm =
                savedState?.getString(STATE_FORM)?.let {
                    Gson().fromJson(it, FolderForm::class.java)
                }
            val folder =
                if (restoredForm != null) {
                    Gson().fromJson(savedState.getString(STATE_FOLDER), Folder::class.java)
                } else if (args.create) {
                    newFolder(args)
                } else {
                    Log.d(TAG, "Initializing edit mode: folder.id=${args.folderId}")
                    configRouter.getFolders(host.restApi).firstOrNull { it.id == args.folderId }
                }
            if (folder == null) {
                Log.w(TAG, "Folder not found in API update, maybe it was deleted?")
                return null
            }
            val folderUri = savedState?.getString(STATE_FOLDER_URI)?.let(Uri::parse)
            val controller =
                FolderEditController(
                    context,
                    preferences,
                    configRouter,
                    host,
                    args.create,
                    folder,
                    folderUri.takeIf { restoredForm != null },
                )
            controller.load(args, restoredForm, savedState)
            return controller
        }

        private fun newFolder(args: FolderEditArgs): Folder =
            Folder().apply {
                id = args.folderId ?: generateRandomFolderId()
                label = args.label?.trim().orEmpty()
                paused = false
                type =
                    if (args.receiveEncrypted) Constants.FOLDER_TYPE_RECEIVE_ENCRYPTED
                    else Constants.FOLDER_TYPE_SEND_RECEIVE
                minDiskFree = Folder.MinDiskFree()
                versioning =
                    Folder.Versioning().apply {
                        type = "trashcan"
                        params = mapOf("cleanoutDays" to TRASHCAN_CLEANOUT_DAYS.toString())
                        cleanupIntervalS = 0
                        fsPath = ""
                        fsType = "basic"
                    }
            }
    }

    private fun load(args: FolderEditArgs, restoredForm: FolderForm?, savedState: Bundle?) {
        var initial =
            restoredForm
                ?: FolderForm.from(
                    folder,
                    // The REST API may not be bound yet.
                    configRouter.getDevices(null, false),
                    runScript =
                        preferences.getBoolean(
                            Constants.dynPrefObjectFolderRunScript(folder.id.orEmpty()),
                            false,
                        ),
                    customSyncConditions =
                        !createMode &&
                            preferences.getBoolean(customSyncConditionsPref(folder.id), false),
                    changed = createMode,
                )
        if (restoredForm == null && args.offeringDeviceId != null) {
            folder.addDevice(SharedWithDevice().apply { deviceID = args.offeringDeviceId })
            initial =
                initial.copy(
                    shares = FolderForm.sharesOf(folder, configRouter.getDevices(null, false)),
                    changed = true,
                )
        }
        form = initial
        info = info.copy(expertMode = preferences.getBoolean(Constants.PREF_EXPERT_MODE, false))
        refreshStatus()
        dialog =
            savedState?.getString(STATE_DIALOG)?.let(FolderDialog::valueOf) ?: FolderDialog.NONE
        checkWriteAccess(initial.path)
        if (!createMode && restoredForm == null) {
            configRouter.getFolderIgnoreList(null, folder) { ignoreList ->
                form = form?.copy(ignoreList = ignoreList.ignore?.joinToString("\n").orEmpty())
            }
        }
    }
}
