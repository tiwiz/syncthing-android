package com.nutomic.syncthingandroid.activities

import androidx.compose.runtime.Immutable
import com.google.gson.Gson
import com.nutomic.syncthingandroid.model.Device
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.util.splitLikeJava

/** Whether a folder is shared with the device, and the folder's password for it. */
@Immutable
internal data class FolderShare(
    val folderId: String,
    val label: String,
    val shared: Boolean,
    val encryptionPassword: String,
)

/** What the device screen edits; [changed] once the user changed anything. */
@Immutable
internal data class DeviceForm(
    val deviceId: String,
    val name: String,
    val addresses: String,
    val compression: String,
    val introducer: Boolean,
    val autoAcceptFolders: Boolean,
    val paused: Boolean,
    val untrusted: Boolean,
    val customSyncConditions: Boolean,
    val shares: List<FolderShare>,
    val changed: Boolean,
) {
    /** [device] with the edited values, on a copy. */
    fun applyTo(device: Device): Device =
        device.copyWithGson().also {
            it.deviceID = deviceId
            it.name = name
            it.addresses = persistableAddresses(addresses)
            it.compression = compression
            it.introducer = introducer
            it.autoAcceptFolders = autoAcceptFolders
            it.paused = paused
            it.untrusted = untrusted
        }

    companion object {
        val DYNAMIC_ADDRESS = listOf("dynamic")

        fun from(
            device: Device,
            folders: List<Folder>,
            customSyncConditions: Boolean,
            changed: Boolean,
        ) =
            DeviceForm(
                deviceId = device.deviceID.orEmpty(),
                name = device.name,
                addresses = displayableAddresses(device.addresses),
                compression = device.compression,
                introducer = device.introducer,
                autoAcceptFolders = device.autoAcceptFolders,
                paused = device.paused,
                untrusted = device.untrusted,
                customSyncConditions = customSyncConditions,
                shares = sharesOf(device.deviceID, folders),
                changed = changed,
            )

        fun sharesOf(deviceId: String?, folders: List<Folder>): List<FolderShare> =
            folders.map { folder ->
                val share = deviceId?.let { folder.getDevice(it) }
                FolderShare(
                    folderId = folder.id.orEmpty(),
                    label = folder.toString(),
                    shared = share != null,
                    encryptionPassword = share?.encryptionPassword.orEmpty(),
                )
            }

        /** The addresses as one line. */
        fun displayableAddresses(addresses: List<String>?): String =
            addresses?.joinToString(", ").orEmpty()

        /**
         * The user's line as addresses, fault-tolerant: commas, semicolons and spaces all separate
         * ("tcp4://192.168.1.67:2222; dynamic"). Nothing means dynamic.
         */
        fun persistableAddresses(userInput: String): List<String> {
            if (userInput.isEmpty()) return DYNAMIC_ADDRESS
            return userInput
                .replace(",", " ")
                .replace(";", " ")
                .replace(Regex("\\s+"), ", ")
                .splitLikeJava(", ")
        }
    }
}

private fun Device.copyWithGson(): Device =
    Gson().run { fromJson(toJson(this@copyWithGson), Device::class.java) }
