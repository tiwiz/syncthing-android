package com.nutomic.syncthingandroid.activities

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.google.gson.Gson
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.model.Device
import com.nutomic.syncthingandroid.model.DiscoveredDevice
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.RestApi
import com.nutomic.syncthingandroid.service.TestData
import com.nutomic.syncthingandroid.util.Compression
import com.nutomic.syncthingandroid.util.ConfigRouter
import com.nutomic.syncthingandroid.util.isRunningOnTV
import com.nutomic.syncthingandroid.views.DeviceRowState

/** The screens a device screen opens for a result. */
internal enum class DeviceRequest {
    SCAN_QR,
    ADD_FOLDER,
}

/** Where a device screen lives: its own activity, or a pane of the main screen. */
internal interface DeviceEditHost {
    /** The REST API, or null while Syncthing does not run. */
    val restApi: RestApi?

    /** Opens [intent] for [request]; the result goes to [DeviceEditController.onResult]. */
    fun launch(request: DeviceRequest, intent: Intent)

    fun open(intent: Intent)

    /** The device ID as text and QR code. */
    fun showDeviceId(name: String, deviceId: String)

    /** Closes the screen; [saved] tells the caller whether the device changed. */
    fun close(saved: Boolean)

    /** Goes back as if the screen had nothing to ask. */
    fun leave()
}

/** What a device screen is opened for. */
internal data class DeviceEditArgs(
    val create: Boolean,
    val deviceId: String?,
    val name: String? = null,
) {
    companion object {
        fun of(intent: Intent) =
            DeviceEditArgs(
                create = intent.getBooleanExtra(DeviceActivity.EXTRA_IS_CREATE, false),
                deviceId = intent.getStringExtra(DeviceActivity.EXTRA_DEVICE_ID),
                name = intent.getStringExtra(DeviceActivity.EXTRA_DEVICE_NAME),
            )
    }
}

/**
 * A device screen's state and what its user can do: loads the device or creates one, keeps the
 * form, checks and saves it, with the folders it shares. The [host] does what needs an activity.
 */
internal class DeviceEditController
private constructor(
    private val context: Context,
    private val preferences: SharedPreferences,
    private val configRouter: ConfigRouter,
    private val host: DeviceEditHost,
    private val createMode: Boolean,
    /** The device as loaded or created; the form's values are applied to it when saving. */
    private val device: Device,
) {
    /** The folders the device can share, as loaded. */
    private var folders: List<Folder> = emptyList()

    var form by mutableStateOf<DeviceForm?>(null)
        private set

    var info by
        mutableStateOf(
            DeviceScreenInfo(createMode, false, !isRunningOnTV(context), null, null, null, true)
        )
        private set

    var dialog by mutableStateOf(DeviceDialog.NONE)
        private set

    /** Keeps what [create] needs to restore the screen. */
    fun saveState(outState: Bundle) {
        outState.putString(STATE_DEVICE, Gson().toJson(device))
        outState.putString(STATE_FORM, Gson().toJson(form))
        outState.putString(STATE_DIALOG, dialog.name)
    }

    /** Shows the saved device's state as Syncthing reports it; nothing for a new device. */
    fun refreshStatus() {
        if (createMode) return
        val id = device.deviceID
        val restApi = host.restApi
        val saved =
            configRouter.getDevices(restApi, false).firstOrNull { it.deviceID == id } ?: return
        val running = restApi?.takeIf { it.isConfigLoaded }
        val row =
            DeviceRowState.of(
                context,
                saved,
                // The screen does not show when the device was last seen.
                lastSeen = "",
                configRouter.getSharedFolders(restApi, id),
                running?.let {
                    DeviceRowState.Status(
                        it.getRemoteDeviceStatus(id),
                        it.getRemoteDeviceCompletion(id),
                        it.getRemoteDeviceNeedBytes(id),
                    )
                },
            )
        info = info.copy(status = EditStatus(row.tone, row.status, row.progress))
    }

    /** A screen opened through [DeviceEditHost.launch] returned [result]. */
    fun onResult(request: DeviceRequest, result: ActivityResult) {
        if (result.resultCode != Activity.RESULT_OK) return
        when (request) {
            DeviceRequest.SCAN_QR -> {
                val scanned = result.data?.getStringExtra(QRScannerActivity.QR_RESULT_ARG)
                val current = form
                if (scanned != null && current != null) {
                    val id = if (Constants.ENABLE_TEST_DATA) TestData.DEVICE_A_ID else scanned
                    form = current.copy(deviceId = id, changed = true)
                }
            }
            DeviceRequest.ADD_FOLDER -> loadFolders()
        }
    }

    /** Syncthing runs: shows the device's connection and, for a new one, what discovery found. */
    fun onApiAvailable(restApi: RestApi) {
        // The device's connection from the cache.
        val connection = restApi.getRemoteDeviceStatus(device.deviceID)
        if (connection.at.isNotEmpty()) {
            info =
                info.copy(
                    currentAddress = connection.address,
                    syncthingVersion = connection.clientVersion,
                )
        }
        if (createMode) {
            queryDiscoveredDevices(restApi)
        }
    }

    private fun customSyncConditionsPref(deviceId: String? = device.deviceID) =
        Constants.dynPrefObjectCustomSyncConditions(Constants.PREF_OBJECT_PREFIX_DEVICE + deviceId)

    private fun loadFolders() {
        folders = configRouter.getFolders(host.restApi)
        form = form?.let { current ->
            val kept = current.shares.associateBy { it.folderId }
            current.copy(
                shares =
                    DeviceForm.sharesOf(current.deviceId, folders).map { kept[it.folderId] ?: it }
            )
        }
    }

    private fun toast(text: Int, length: Int = Toast.LENGTH_LONG) =
        Toast.makeText(context, text, length).show()

    private fun save(current: DeviceForm) {
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
            toast(error)
            return
        }
        if (!createMode && !current.changed) {
            // Nothing to save.
            host.close(saved = false)
            return
        }
        saveFolderShares(current, edited)
        if (createMode) {
            Log.v(TAG, "onSave: Adding device with ID = '${edited.deviceID}'")
            configRouter.updateDevice(host.restApi, edited)
            host.close(saved = true)
            return
        }
        Log.v(TAG, "onSave: Updating device with ID = '${edited.deviceID}'")
        preferences.edit {
            putBoolean(customSyncConditionsPref(edited.deviceID), current.customSyncConditions)
        }
        configRouter.updateDevice(host.restApi, edited)
        host.close(saved = true)
    }

    /**
     * Shares or unshares the folders whose share or encryption password changed. Each one is a
     * config change of its own, so the others are left alone.
     */
    private fun saveFolderShares(current: DeviceForm, edited: Device) {
        val shares = current.shares.associateBy { it.folderId }
        // As saved now: the list loaded with the screen may come from config.xml.
        for (folder in configRouter.getFolders(host.restApi)) {
            val share = shares[folder.id]?.takeIf { it.differsFrom(folder, edited.deviceID) }
            if (share == null) continue
            if (share.shared) {
                folder.addDevice(edited)
                checkNotNull(folder.getDevice(edited.deviceID)).encryptionPassword =
                    share.encryptionPassword
            } else {
                folder.removeDevice(edited.deviceID)
            }
            configRouter.updateFolder(host.restApi, folder)
        }
    }

    /** Whether this share or its encryption password is not what [folder] has for [deviceId]. */
    private fun FolderShare.differsFrom(folder: Folder, deviceId: String?): Boolean {
        val saved = folder.getDevice(deviceId)
        return if (shared) saved?.encryptionPassword.orEmpty() != encryptionPassword
        else saved != null
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

    val actions: DeviceScreenActions =
        object : DeviceScreenActions {
            override fun onFormChange(form: DeviceForm) {
                // A device picked from the discovered ones: the list has done its job.
                if (info.discovered?.any { it.deviceId == form.deviceId } == true) {
                    info = info.copy(discovered = null)
                }
                this@DeviceEditController.form = form
            }

            override fun onSave() {
                form?.let(::save)
            }

            override fun onDelete() {
                dialog = DeviceDialog.DELETE
            }

            override fun onBack() {
                if (form?.changed == true) dialog = DeviceDialog.DISCARD else host.leave()
            }

            override fun onScanQr() {
                host.launch(DeviceRequest.SCAN_QR, QRScannerActivity.intent(context))
            }

            override fun onShowDeviceId() {
                val deviceId = form?.deviceId
                if (deviceId.isNullOrEmpty()) {
                    toast(R.string.could_not_access_deviceid, Toast.LENGTH_SHORT)
                    return
                }
                host.showDeviceId(device.displayName.trim(), deviceId)
            }

            override fun onRefreshDiscovered() {
                host.restApi?.let(::queryDiscoveredDevices)
            }

            override fun onAddFolder() {
                host.launch(DeviceRequest.ADD_FOLDER, FolderActivity.createIntent(context))
            }

            override fun onOpenSyncConditions() {
                host.open(
                    SyncConditionsActivity.createIntent(
                        context,
                        Constants.PREF_OBJECT_PREFIX_DEVICE + device.deviceID,
                        device.name,
                    )
                )
            }

            override fun onDialog(dialog: DeviceDialog) {
                this@DeviceEditController.dialog = dialog
            }

            override fun onConfirmDialog(dialog: DeviceDialog) {
                this@DeviceEditController.dialog = DeviceDialog.NONE
                when (dialog) {
                    DeviceDialog.DELETE -> {
                        configRouter.removeDevice(host.restApi, checkNotNull(device.deviceID))
                        form = form?.copy(changed = false)
                        host.close(saved = true)
                    }
                    DeviceDialog.DISCARD -> host.close(saved = false)
                    DeviceDialog.COMPRESSION,
                    DeviceDialog.NONE -> Unit
                }
            }
        }

    private fun load(restoredForm: DeviceForm?, savedState: Bundle?) {
        // The REST API may not be bound yet.
        folders = configRouter.getFolders(null)
        form =
            restoredForm
                ?: DeviceForm.from(
                    device,
                    folders,
                    customSyncConditions =
                        !createMode && preferences.getBoolean(customSyncConditionsPref(), false),
                    changed = createMode,
                )
        info = info.copy(expertMode = preferences.getBoolean(Constants.PREF_EXPERT_MODE, false))
        refreshStatus()
        dialog =
            savedState?.getString(STATE_DIALOG)?.let(DeviceDialog::valueOf) ?: DeviceDialog.NONE
    }

    companion object {
        private const val TAG = "DeviceEditController"
        private const val STATE_DEVICE = "device"
        private const val STATE_FORM = "form"
        private const val STATE_DIALOG = "dialog"

        /**
         * The controller of the device [args] asks for, restored from [savedState] if any; null
         * when the device to edit is gone.
         */
        @Suppress("LongParameterList") // What a device screen needs, from its host.
        fun create(
            context: Context,
            preferences: SharedPreferences,
            configRouter: ConfigRouter,
            host: DeviceEditHost,
            args: DeviceEditArgs,
            savedState: Bundle?,
        ): DeviceEditController? {
            val restored =
                savedState?.getString(STATE_FORM)?.let {
                    Gson().fromJson(it, DeviceForm::class.java)
                }
            val device =
                restored?.let {
                    Gson().fromJson(savedState.getString(STATE_DEVICE), Device::class.java)
                }
                    ?: if (args.create) {
                        newDevice(context, args)
                    } else {
                        Log.d(TAG, "Initializing edit mode: deviceID=${args.deviceId}")
                        configRouter.getDevices(null, false).firstOrNull {
                            it.deviceID == args.deviceId
                        }
                    }
            if (device == null) {
                Log.w(TAG, "Device not found in API update, maybe it was deleted?")
                return null
            }
            return DeviceEditController(
                    context,
                    preferences,
                    configRouter,
                    host,
                    args.create,
                    device,
                )
                .also { it.load(restored, savedState) }
        }

        private fun newDevice(context: Context, args: DeviceEditArgs): Device =
            Device().apply {
                // ConfigXml.saveChanges fails to transform a device without a name.
                name = args.name.orEmpty()
                deviceID = args.deviceId
                addresses = DeviceForm.DYNAMIC_ADDRESS
                compression = Compression.METADATA.getValue(context)
            }
    }
}
