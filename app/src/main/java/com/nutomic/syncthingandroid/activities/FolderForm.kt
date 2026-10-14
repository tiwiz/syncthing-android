package com.nutomic.syncthingandroid.activities

import androidx.compose.runtime.Immutable
import com.google.gson.Gson
import com.nutomic.syncthingandroid.model.Device
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.model.SharedWithDevice
import com.nutomic.syncthingandroid.util.splitLikeJava

/** Whether the folder is shared with a device, and the device's password for it. */
@Immutable
internal data class DeviceShare(
    val deviceId: String,
    val name: String,
    val introducedBy: String,
    val shared: Boolean,
    val encryptionPassword: String,
)

/**
 * What the folder screen edits. [versioningType] is "" for no versioning. [changed] once the user
 * changed anything, [ignoreListChanged] once they edited the ignore list.
 */
@Immutable
internal data class FolderForm(
    val label: String,
    val id: String,
    val path: String,
    val type: String,
    val order: String?,
    val versioningType: String,
    val versioningParams: Map<String, String>,
    val fsWatcherEnabled: Boolean,
    val paused: Boolean,
    val ignoreDelete: Boolean,
    val runScript: Boolean,
    val customSyncConditions: Boolean,
    val shares: List<DeviceShare>,
    val ignoreList: String,
    val ignoreListChanged: Boolean,
    val changed: Boolean,
) {
    /** [folder] with the edited values, on a copy. */
    fun applyTo(folder: Folder): Folder =
        Gson()
            .run { fromJson(toJson(folder), Folder::class.java) }
            .also { edited ->
                edited.label = label.trim()
                edited.id = id
                edited.path = path
                edited.type = type
                edited.order = order
                edited.fsWatcherEnabled = fsWatcherEnabled
                edited.paused = paused
                edited.ignoreDelete = ignoreDelete
                edited.versioning =
                    (edited.versioning ?: Folder.Versioning()).also {
                        it.type = versioningType
                        it.params = versioningParams
                    }
                for (share in shares) {
                    if (share.shared) {
                        if (edited.getDevice(share.deviceId) == null) {
                            edited.addDevice(
                                SharedWithDevice().apply {
                                    deviceID = share.deviceId
                                    introducedBy = share.introducedBy
                                }
                            )
                        }
                        checkNotNull(edited.getDevice(share.deviceId)).encryptionPassword =
                            share.encryptionPassword
                    } else {
                        edited.removeDevice(share.deviceId)
                    }
                }
            }

    /** The ignore list as lines, as Syncthing takes it. */
    fun ignoreLines(): Array<String> = ignoreList.splitLikeJava("\n").toTypedArray()

    /** The form after the versioning screen's result: "none" clears the versioning. */
    fun withVersioning(result: Map<String, String>): FolderForm {
        val type = result["type"]
        val params = result - "type"
        return if (type == "none") {
            copy(versioningType = "", versioningParams = emptyMap(), changed = true)
        } else {
            copy(
                versioningType = type.orEmpty(),
                versioningParams = versioningParams + params,
                changed = true,
            )
        }
    }

    companion object {
        fun from(
            folder: Folder,
            devices: List<Device>,
            runScript: Boolean,
            customSyncConditions: Boolean,
            changed: Boolean,
        ) =
            FolderForm(
                label = folder.label,
                id = folder.id.orEmpty(),
                path = folder.path.orEmpty(),
                type = folder.type,
                order = folder.order,
                versioningType = folder.versioning?.type.orEmpty(),
                versioningParams = folder.versioning?.params.orEmpty(),
                fsWatcherEnabled = folder.fsWatcherEnabled,
                paused = folder.paused,
                ignoreDelete = folder.ignoreDelete,
                runScript = runScript,
                customSyncConditions = customSyncConditions,
                shares = sharesOf(folder, devices),
                ignoreList = "",
                ignoreListChanged = false,
                changed = changed,
            )

        fun sharesOf(folder: Folder, devices: List<Device>): List<DeviceShare> =
            devices.map { device ->
                val share = folder.getDevice(device.deviceID)
                DeviceShare(
                    deviceId = device.deviceID.orEmpty(),
                    name = device.displayName,
                    introducedBy = device.introducedBy,
                    shared = share != null,
                    encryptionPassword = share?.encryptionPassword.orEmpty(),
                )
            }
    }
}
