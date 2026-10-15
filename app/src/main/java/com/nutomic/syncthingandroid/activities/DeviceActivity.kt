package com.nutomic.syncthingandroid.activities

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.google.gson.Gson
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.SyncthingApp
import com.nutomic.syncthingandroid.fragments.DeviceIdDialogFragment
import com.nutomic.syncthingandroid.model.Device
import com.nutomic.syncthingandroid.model.DiscoveredDevice
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.RestApi
import com.nutomic.syncthingandroid.service.SyncthingServiceBinder
import com.nutomic.syncthingandroid.service.TestData
import com.nutomic.syncthingandroid.theme.ApplicationTheme
import com.nutomic.syncthingandroid.util.Compression
import com.nutomic.syncthingandroid.util.ConfigRouter
import com.nutomic.syncthingandroid.util.isRunningOnTV
import javax.inject.Inject

/** Shows a device's details and changes them, or adds a new device. */
class DeviceActivity : SyncthingActivity() {

    @Inject lateinit var preferences: SharedPreferences

    private lateinit var configRouter: ConfigRouter

    /** The device as loaded or created; the form's values are applied to it when saving. */
    private lateinit var device: Device

    /** The folders the device can share, as loaded. */
    private var folders: List<Folder> = emptyList()

    private var isCreateMode = false

    private var form by mutableStateOf<DeviceForm?>(null)
    private var info by
        mutableStateOf(DeviceScreenInfo(false, false, false, null, null, null, true))
    private var dialog by mutableStateOf(DeviceDialog.NONE)

    private val actions: DeviceScreenActions =
        object : DeviceScreenActions {
            override fun onFormChange(form: DeviceForm) {
                // A device picked from the discovered ones: the list has done its job.
                if (info.discovered?.any { it.deviceId == form.deviceId } == true) {
                    info = info.copy(discovered = null)
                }
                this@DeviceActivity.form = form
            }

            override fun onSave() {
                val current = form ?: return
                val edited = current.applyTo(device)
                // Validate fields.
                val error =
                    when {
                        edited.deviceID.isNullOrEmpty() -> R.string.device_id_required
                        !edited.checkDeviceID() -> R.string.device_id_invalid
                        !edited.checkDeviceAddresses() -> R.string.device_addresses_invalid
                        else -> null
                    }
                if (error != null) {
                    Toast.makeText(this@DeviceActivity, error, Toast.LENGTH_LONG).show()
                    return
                }
                saveFolderShares(current, edited)
                if (isCreateMode) {
                    Log.v(TAG, "onSave: Adding device with ID = '${edited.deviceID}'")
                    configRouter.updateDevice(api, edited)
                    setResult(Activity.RESULT_OK)
                    finish()
                    return
                }
                if (!current.changed) {
                    // Nothing to save.
                    setResult(Activity.RESULT_CANCELED)
                    finish()
                    return
                }
                Log.v(TAG, "onSave: Updating device with ID = '${edited.deviceID}'")
                preferences.edit {
                    putBoolean(
                        customSyncConditionsPref(edited.deviceID),
                        current.customSyncConditions,
                    )
                }
                configRouter.updateDevice(api, edited)
                setResult(Activity.RESULT_OK)
                finish()
            }

            override fun onDelete() {
                dialog = DeviceDialog.DELETE
            }

            override fun onBack() {
                if (form?.changed == true) {
                    dialog = DeviceDialog.DISCARD
                } else {
                    backPressedCallback.isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }

            override fun onScanQr() {
                scanQr.launch(QRScannerActivity.intent(this@DeviceActivity))
            }

            override fun onShowDeviceId() {
                val deviceId = form?.deviceId
                if (deviceId.isNullOrEmpty()) {
                    Toast.makeText(
                            this@DeviceActivity,
                            R.string.could_not_access_deviceid,
                            Toast.LENGTH_SHORT,
                        )
                        .show()
                    return
                }
                DeviceIdDialogFragment.show(
                    supportFragmentManager,
                    device.displayName.trim(),
                    deviceId,
                    false,
                )
            }

            override fun onRefreshDiscovered() {
                api?.let(::queryDiscoveredDevices)
            }

            override fun onAddFolder() {
                addFolder.launch(FolderActivity.createIntent(this@DeviceActivity))
            }

            override fun onOpenSyncConditions() {
                startActivity(
                    SyncConditionsActivity.createIntent(
                        this@DeviceActivity,
                        Constants.PREF_OBJECT_PREFIX_DEVICE + device.deviceID,
                        device.name,
                    )
                )
            }

            override fun onDialog(dialog: DeviceDialog) {
                this@DeviceActivity.dialog = dialog
            }

            override fun onConfirmDialog(dialog: DeviceDialog) {
                this@DeviceActivity.dialog = DeviceDialog.NONE
                when (dialog) {
                    DeviceDialog.DELETE -> {
                        configRouter.removeDevice(api, checkNotNull(device.deviceID))
                        form = form?.copy(changed = false)
                        setResult(Activity.RESULT_OK)
                        finish()
                    }
                    DeviceDialog.DISCARD -> {
                        setResult(Activity.RESULT_CANCELED)
                        finish()
                    }
                    DeviceDialog.COMPRESSION,
                    DeviceDialog.NONE -> Unit
                }
            }
        }

    private val backPressedCallback =
        object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = actions.onBack()
        }

    private val scanQr =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val scanned = result.data?.getStringExtra(QRScannerActivity.QR_RESULT_ARG)
            val current = form
            if (result.resultCode == Activity.RESULT_OK && scanned != null && current != null) {
                val id = if (Constants.ENABLE_TEST_DATA) TestData.DEVICE_A_ID else scanned
                form = current.copy(deviceId = id, changed = true)
            }
        }

    private val addFolder =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                loadFolders()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        configRouter = ConfigRouter(this)
        super.onCreate(savedInstanceState)
        (application as SyncthingApp).component().inject(this)
        isCreateMode = intent.getBooleanExtra(EXTRA_IS_CREATE, false)

        val restored =
            savedInstanceState?.getString(STATE_FORM)?.let {
                Gson().fromJson(it, DeviceForm::class.java)
            }
        val loaded =
            restored?.let {
                Gson().fromJson(savedInstanceState.getString(STATE_DEVICE), Device::class.java)
            } ?: if (isCreateMode) newDevice() else findDevice()
        if (loaded == null) {
            Log.w(TAG, "Device not found in API update, maybe it was deleted?")
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        device = loaded
        // getApi() is not available yet (onCreate > onPostCreate > onServiceConnected).
        folders = configRouter.getFolders(null)
        form =
            restored
                ?: DeviceForm.from(
                    device,
                    folders,
                    customSyncConditions =
                        !isCreateMode && preferences.getBoolean(customSyncConditionsPref(), false),
                    changed = isCreateMode,
                )
        info =
            info.copy(
                createMode = isCreateMode,
                expertMode = preferences.getBoolean(Constants.PREF_EXPERT_MODE, false),
                canScanQr = !isRunningOnTV(this),
            )
        dialog =
            savedInstanceState?.getString(STATE_DIALOG)?.let(DeviceDialog::valueOf)
                ?: DeviceDialog.NONE

        setContent {
            ApplicationTheme {
                form?.let {
                    DeviceEditScreen(form = it, info = info, dialog = dialog, actions = actions)
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, backPressedCallback)
    }

    private fun newDevice(): Device =
        Device().apply {
            // ConfigXml.saveChanges fails to transform a device without a name.
            name = intent.getStringExtra(EXTRA_DEVICE_NAME).orEmpty()
            deviceID = intent.getStringExtra(EXTRA_DEVICE_ID)
            addresses = DeviceForm.DYNAMIC_ADDRESS
            compression = Compression.METADATA.getValue(this@DeviceActivity)
        }

    private fun findDevice(): Device? {
        val passedId = intent.getStringExtra(EXTRA_DEVICE_ID)
        Log.d(TAG, "Initializing edit mode: deviceID=$passedId")
        return configRouter.getDevices(null, false).firstOrNull { it.deviceID == passedId }
    }

    private fun customSyncConditionsPref(deviceId: String? = device.deviceID) =
        Constants.dynPrefObjectCustomSyncConditions(Constants.PREF_OBJECT_PREFIX_DEVICE + deviceId)

    private fun loadFolders() {
        folders = configRouter.getFolders(api)
        form = form?.let { current ->
            val kept = current.shares.associateBy { it.folderId }
            current.copy(
                shares =
                    DeviceForm.sharesOf(current.deviceId, folders).map { kept[it.folderId] ?: it }
            )
        }
    }

    override fun onServiceConnected(componentName: ComponentName, iBinder: IBinder) {
        super.onServiceConnected(componentName, iBinder)
        val service = (iBinder as SyncthingServiceBinder).service
        service.notificationHandler.cancelConsentNotification(
            intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        )
        val restApi = service.api ?: return
        // The device's connection from the cache.
        val connection = restApi.getRemoteDeviceStatus(device.deviceID)
        if (connection.at.isNotEmpty()) {
            info =
                info.copy(
                    currentAddress = connection.address,
                    syncthingVersion = connection.clientVersion,
                )
        }
        if (isCreateMode) {
            queryDiscoveredDevices(restApi)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        service
            ?.notificationHandler
            ?.cancelConsentNotification(intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::device.isInitialized) {
            outState.putString(STATE_DEVICE, Gson().toJson(device))
            outState.putString(STATE_FORM, Gson().toJson(form))
            outState.putString(STATE_DIALOG, dialog.name)
        }
    }

    /** Shares or unshares every folder with the device, with its encryption password. */
    private fun saveFolderShares(current: DeviceForm, edited: Device) {
        val shares = current.shares.associateBy { it.folderId }
        for (folder in folders) {
            val share = shares[folder.id] ?: continue
            if (share.shared) {
                folder.addDevice(edited)
                checkNotNull(folder.getDevice(edited.deviceID)).encryptionPassword =
                    share.encryptionPassword
            } else {
                folder.removeDevice(edited.deviceID)
            }
            configRouter.updateFolder(api, folder)
        }
    }

    /** Asks Syncthing for the devices local discovery found; create mode only. */
    private fun queryDiscoveredDevices(restApi: RestApi) {
        if (!restApi.isConfigLoaded) {
            return
        }
        restApi.getDiscoveredDevices(::onReceiveDiscoveredDevices)
    }

    private fun onReceiveDiscoveredDevices(discoveredDevices: Map<String, DiscoveredDevice>) {
        // With an id entered, do not offer others; also after the user picked one and rotated.
        if (form?.deviceId.isNullOrEmpty().not()) {
            info = info.copy(discovered = null)
            return
        }
        info =
            info.copy(
                discovered =
                    discoveredDevices.map { (id, discovered) ->
                        DiscoveredEntry(id, discovered.addresses.orEmpty().joinToString(", "))
                    },
                localDiscoveryEnabled = configRouter.getOptions(null).localAnnounceEnabled,
            )
    }

    companion object {
        const val EXTRA_NOTIFICATION_ID = ".activities.DeviceActivity.NOTIFICATION_ID"
        const val EXTRA_DEVICE_ID = ".activities.DeviceActivity.DEVICE_ID"
        const val EXTRA_DEVICE_NAME = ".activities.DeviceActivity.DEVICE_NAME"
        const val EXTRA_IS_CREATE = ".activities.DeviceActivity.IS_CREATE"

        const val DEVICE_ADD_CODE = 401

        private const val TAG = "DeviceActivity"
        private const val STATE_DEVICE = "device"
        private const val STATE_FORM = "form"
        private const val STATE_DIALOG = "dialog"

        @JvmStatic
        fun createIntent(context: Context): Intent =
            Intent(context, DeviceActivity::class.java).putExtra(EXTRA_IS_CREATE, true)
    }
}
