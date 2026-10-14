package com.nutomic.syncthingandroid.activities

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.gson.Gson
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.SyncthingApp
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.model.SharedWithDevice
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.RunConditionMonitor
import com.nutomic.syncthingandroid.service.SyncthingServiceBinder
import com.nutomic.syncthingandroid.theme.ApplicationTheme
import com.nutomic.syncthingandroid.util.ConfigRouter
import com.nutomic.syncthingandroid.util.FileUtils
import com.nutomic.syncthingandroid.util.SafUtils
import com.nutomic.syncthingandroid.util.formatPath
import com.nutomic.syncthingandroid.util.nativeBinaryCanWriteToPath
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.launch

/** Shows a folder's details and changes them, or adds a new folder. */
class FolderActivity : SyncthingActivity() {

    @Inject lateinit var preferences: SharedPreferences

    private lateinit var configRouter: ConfigRouter

    /** The folder as loaded or created; the form's values are applied to it when saving. */
    private lateinit var folder: Folder

    /** The SAF tree the user picked for a new folder, with write access. */
    private var folderUri: Uri? = null

    private var isCreateMode = false

    private var form by mutableStateOf<FolderForm?>(null)
    private var info by mutableStateOf(FolderScreenInfo(false, false, null, false, false))
    private var dialog by mutableStateOf(FolderDialog.NONE)

    private val backPressedCallback =
        object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = actions.onBack()
        }

    private val pickTree =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult(), ::onTreePicked)
    private val pickDirectory =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val directory = result.data?.getStringExtra(FolderPickerActivity.EXTRA_RESULT_DIRECTORY)
            if (result.resultCode == Activity.RESULT_OK && directory != null) {
                setPath(FileUtils.cutTrailingSlash(directory))
            }
        }
    private val chooseFolderType =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val type =
                result.data?.getStringExtra(FolderTypeDialogActivity.EXTRA_RESULT_FOLDER_TYPE)
            if (result.resultCode == Activity.RESULT_OK && type != null) onFolderTypeChosen(type)
        }
    private val choosePullOrder =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val order =
                    result.data?.getStringExtra(PullOrderDialogActivity.EXTRA_RESULT_PULL_ORDER)
                form = form?.copy(order = order, changed = true)
            }
        }
    private val chooseVersioning =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val extras = result.data?.extras
            if (result.resultCode == Activity.RESULT_OK && extras != null) {
                val values = extras.keySet().associateWith { extras.getString(it).orEmpty() }
                form = form?.withVersioning(values)
            }
        }
    private val addDevice =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) loadDevices()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        configRouter = ConfigRouter(this)
        super.onCreate(savedInstanceState)
        (application as SyncthingApp).component().inject(this)
        isCreateMode = intent.getBooleanExtra(EXTRA_IS_CREATE, false)

        val restoredForm =
            savedInstanceState?.getString(STATE_FORM)?.let {
                Gson().fromJson(it, FolderForm::class.java)
            }
        val loaded =
            if (restoredForm != null) {
                folderUri = savedInstanceState.getString(STATE_FOLDER_URI)?.let(Uri::parse)
                Gson().fromJson(savedInstanceState.getString(STATE_FOLDER), Folder::class.java)
            } else if (isCreateMode) {
                newFolder()
            } else {
                findFolder()
            }
        if (loaded == null) {
            Log.w(TAG, "Folder not found in API update, maybe it was deleted?")
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        folder = loaded
        var initial =
            restoredForm
                ?: FolderForm.from(
                    folder,
                    // getApi() is not available yet (onCreate > onPostCreate > onServiceConnected).
                    configRouter.getDevices(null, false),
                    runScript =
                        preferences.getBoolean(
                            Constants.dynPrefObjectFolderRunScript(folder.id.orEmpty()),
                            false,
                        ),
                    customSyncConditions =
                        !isCreateMode &&
                            preferences.getBoolean(customSyncConditionsPref(folder.id), false),
                    changed = isCreateMode,
                )
        // A folder offered by a device is shared with it right away.
        val offeringDevice = intent.getStringExtra(EXTRA_DEVICE_ID)
        if (restoredForm == null && offeringDevice != null) {
            folder.addDevice(SharedWithDevice().apply { deviceID = offeringDevice })
            initial =
                initial.copy(
                    shares = FolderForm.sharesOf(folder, configRouter.getDevices(null, false)),
                    changed = true,
                )
        }
        form = initial
        info =
            info.copy(
                createMode = isCreateMode,
                expertMode = preferences.getBoolean(Constants.PREF_EXPERT_MODE, false),
                // Android 11 disallows picking "Downloads" and the storage root through SAF.
                canSelectAdvancedDirectory = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
            )
        dialog =
            savedInstanceState?.getString(STATE_DIALOG)?.let(FolderDialog::valueOf)
                ?: FolderDialog.NONE
        checkWriteAccess(initial.path)
        if (!isCreateMode && restoredForm == null) {
            configRouter.getFolderIgnoreList(null, folder) { ignoreList ->
                form = form?.copy(ignoreList = ignoreList.ignore?.joinToString("\n").orEmpty())
            }
        }

        enableEdgeToEdge()
        setContent {
            ApplicationTheme {
                form?.let {
                    FolderEditScreen(form = it, info = info, dialog = dialog, actions = actions)
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, backPressedCallback)
    }

    private fun newFolder(): Folder =
        Folder().apply {
            id = intent.getStringExtra(EXTRA_FOLDER_ID) ?: generateRandomFolderId()
            label = intent.getStringExtra(EXTRA_FOLDER_LABEL)?.trim().orEmpty()
            paused = false
            type =
                if (intent.getBooleanExtra(EXTRA_RECEIVE_ENCRYPTED, false))
                    Constants.FOLDER_TYPE_RECEIVE_ENCRYPTED
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

    private fun findFolder(): Folder? {
        val passedId = intent.getStringExtra(EXTRA_FOLDER_ID)
        Log.d(TAG, "Initializing edit mode: folder.id=$passedId")
        return configRouter.getFolders(null).firstOrNull { it.id == passedId }
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
        val canWrite = nativeBinaryCanWriteToPath(this, path)
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
    private fun onTreePicked(result: ActivityResult) {
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) return
        folderUri = uri
        val targetPath = SafUtils.getAbsolutePathFromSAFUri(this, uri)?.let(::formatPath)
        if (targetPath.isNullOrEmpty() || targetPath == File.separator) {
            folderUri = null
            form = form?.copy(path = "")
            checkWriteAccess("")
            // Suggest a folder on internal or external storage.
            Toast.makeText(this, R.string.toast_invalid_folder_selected, Toast.LENGTH_LONG).show()
            return
        }
        setPath(FileUtils.cutTrailingSlash(targetPath))
    }

    private fun onFolderTypeChosen(type: String) {
        if (!isCreateMode && type == Constants.FOLDER_TYPE_RECEIVE_ENCRYPTED) {
            // Syncthing refuses it too: posting such a config fails with HTTP 500.
            Toast.makeText(
                    this,
                    R.string.folder_type_switch_to_receive_encrypted_not_allowed,
                    Toast.LENGTH_LONG,
                )
                .show()
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
                    FolderForm.sharesOf(folder, configRouter.getDevices(api, false)).map {
                        kept[it.deviceId] ?: it
                    }
            )
    }

    override fun onServiceConnected(componentName: ComponentName, iBinder: IBinder) {
        super.onServiceConnected(componentName, iBinder)
        (iBinder as SyncthingServiceBinder)
            .service
            .notificationHandler
            .cancelConsentNotification(intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))
    }

    override fun onDestroy() {
        super.onDestroy()
        service
            ?.notificationHandler
            ?.cancelConsentNotification(intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::folder.isInitialized) {
            outState.putString(STATE_FOLDER, Gson().toJson(folder))
            outState.putString(STATE_FORM, Gson().toJson(form))
            outState.putString(STATE_DIALOG, dialog.name)
            outState.putString(STATE_FOLDER_URI, folderUri?.toString())
        }
    }

    private fun save(current: FolderForm) {
        val error =
            when {
                current.id.isEmpty() -> R.string.folder_id_required
                current.label.trim().isEmpty() -> R.string.folder_label_required
                current.path.isEmpty() -> R.string.folder_path_required
                else -> null
            }
        if (error != null) {
            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            return
        }
        val edited = current.applyTo(folder)
        val folderId = checkNotNull(edited.id)
        preferences.edit {
            putBoolean(Constants.dynPrefObjectFolderRunScript(folderId), current.runScript)
        }
        if (isCreateMode) {
            Log.v(TAG, "onSave: Adding folder with ID = '$folderId'")
            info = info.copy(saving = true)
            lifecycleScope.launch {
                addFolderWithStruct(this@FolderActivity, folderUri, current.path) {
                    configRouter.addFolder(api, edited)
                }
                // Sync right after adding a folder.
                LocalBroadcastManager.getInstance(applicationContext)
                    .sendBroadcast(
                        Intent(RunConditionMonitor.ACTION_SYNC_TRIGGER_FIRED)
                            .putExtra(RunConditionMonitor.EXTRA_BEGIN_ACTIVE_TIME_WINDOW, true)
                    )
                info = info.copy(saving = false)
                setResult(Activity.RESULT_OK)
                finish()
            }
            return
        }
        if (!current.changed) {
            // Nothing to save.
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        Log.v(TAG, "onSave: Updating folder with ID = '$folderId'")
        preferences.edit {
            putBoolean(customSyncConditionsPref(folderId), current.customSyncConditions)
        }
        val restApi = api
        if (current.ignoreListChanged) {
            configRouter.postFolderIgnoreList(restApi, edited, current.ignoreLines())
        }
        configRouter.updateFolder(restApi, edited)
        setResult(Activity.RESULT_OK)
        finish()
    }

    private fun remove() {
        val folderId = checkNotNull(folder.id)
        configRouter.removeFolder(api, folderId)
        if (folderId == Constants.SYNCTHING_CAMERA_FOLDER_ID) {
            // Withdraw the consent to Syncthing Camera.
            preferences.edit { putBoolean(Constants.PREF_ENABLE_SYNCTHING_CAMERA, false) }
        }
        form = form?.copy(changed = false)
        finish()
    }

    /** The SAF directory picker, starting in the app's media folder or the storage root. */
    private fun pickPath() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        val initialUri =
            listOfNotNull(
                    FileUtils.getExternalFilesDirUri(
                        this,
                        FileUtils.ExternalStorageDirType.INT_MEDIA,
                    ),
                    FileUtils.getInternalStorageRootUri(),
                )
                .firstOrNull { SafUtils.directoryUriExists(this, it) }
        if (initialUri != null) {
            Log.v(TAG, "onPathViewClick: INITIAL_URI = $initialUri")
            intent.putExtra("android.provider.extra.INITIAL_URI", initialUri)
        }
        intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true)
        intent.putExtra("android.content.extra.SHOW_ADVANCED", true)
        try {
            pickTree.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Log.e(
                TAG,
                "onPathViewClick exception, falling back to built-in FolderPickerActivity.",
                e,
            )
            pickDirectory.launch(FolderPickerActivity.createIntent(this, form?.path, null))
        }
    }

    private val actions: FolderScreenActions =
        object : FolderScreenActions {
            override fun onFormChange(form: FolderForm) {
                this@FolderActivity.form = form
            }

            override fun onSave() {
                if (!info.saving) form?.let(::save)
            }

            override fun onBack() {
                when {
                    info.saving -> Unit
                    form?.changed == true -> dialog = FolderDialog.DISCARD
                    else -> {
                        backPressedCallback.isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }

            override fun onPickPath() = pickPath()

            override fun onPickAdvancedDirectory() {
                pickDirectory.launch(
                    FolderPickerActivity.createIntent(this@FolderActivity, form?.path, null)
                )
            }

            override fun onFolderType() {
                val current = form ?: return
                when {
                    current.path.isEmpty() ->
                        Toast.makeText(
                                this@FolderActivity,
                                R.string.folder_path_required,
                                Toast.LENGTH_LONG,
                            )
                            .show()
                    // Read-only folders can only send; the screen explains why.
                    info.canWriteToPath != true ->
                        Toast.makeText(
                                this@FolderActivity,
                                R.string.folder_path_readonly,
                                Toast.LENGTH_LONG,
                            )
                            .show()
                    else ->
                        chooseFolderType.launch(
                            Intent(this@FolderActivity, FolderTypeDialogActivity::class.java)
                                .putExtra(FolderTypeDialogActivity.EXTRA_FOLDER_TYPE, current.type)
                        )
                }
            }

            override fun onPullOrder() {
                choosePullOrder.launch(
                    Intent(this@FolderActivity, PullOrderDialogActivity::class.java)
                        .putExtra(PullOrderDialogActivity.EXTRA_PULL_ORDER, form?.order)
                )
            }

            override fun onVersioning() {
                val current = form ?: return
                val intent = Intent(this@FolderActivity, VersioningDialogActivity::class.java)
                current.versioningParams.forEach { (key, value) -> intent.putExtra(key, value) }
                intent.putExtra("type", current.versioningType.ifEmpty { "none" })
                chooseVersioning.launch(intent)
            }

            override fun onOpenSyncConditions() {
                startActivity(
                    SyncConditionsActivity.createIntent(
                        this@FolderActivity,
                        Constants.PREF_OBJECT_PREFIX_FOLDER + folder.id,
                        folder.label,
                    )
                )
            }

            override fun onAddDevice() {
                addDevice.launch(DeviceActivity.createIntent(this@FolderActivity))
            }

            override fun onDialog(dialog: FolderDialog) {
                this@FolderActivity.dialog = dialog
            }

            override fun onConfirmDialog(dialog: FolderDialog) {
                this@FolderActivity.dialog = FolderDialog.NONE
                when (dialog) {
                    FolderDialog.DELETE -> remove()
                    FolderDialog.DISCARD -> {
                        setResult(Activity.RESULT_CANCELED)
                        finish()
                    }
                    FolderDialog.NONE -> Unit
                }
            }
        }

    companion object {
        const val EXTRA_DEVICE_ID = ".activities.FolderActivity.DEVICE_ID"
        const val EXTRA_FOLDER_ID = ".activities.FolderActivity.FOLDER_ID"
        const val EXTRA_FOLDER_LABEL = ".activities.FolderActivity.FOLDER_LABEL"
        const val EXTRA_IS_CREATE = ".activities.FolderActivity.IS_CREATE"
        const val EXTRA_NOTIFICATION_ID = ".activities.FolderActivity.NOTIFICATION_ID"
        const val EXTRA_RECEIVE_ENCRYPTED = ".activities.FolderActivity.RECEIVE_ENCRYPTED"
        const val EXTRA_REMOTE_ENCRYPTED = ".activities.FolderActivity.REMOTE_ENCRYPTED"

        const val FOLDER_ADD_CODE = 402

        private const val TAG = "FolderActivity"
        private const val TRASHCAN_CLEANOUT_DAYS = 14
        private const val STATE_FOLDER = "folder"
        private const val STATE_FORM = "form"
        private const val STATE_DIALOG = "dialog"
        private const val STATE_FOLDER_URI = "folderUri"

        @JvmStatic
        fun createIntent(context: Context): Intent =
            Intent(context, FolderActivity::class.java).putExtra(EXTRA_IS_CREATE, true)
    }
}
