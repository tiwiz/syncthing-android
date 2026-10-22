package com.nutomic.syncthingandroid.util

import android.content.Context
import com.nutomic.syncthingandroid.model.Device
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.model.FolderIgnoreList
import com.nutomic.syncthingandroid.model.Gui
import com.nutomic.syncthingandroid.model.Options
import com.nutomic.syncthingandroid.service.RestApi

/**
 * Reads and changes the config through the REST API while Syncthing runs and its config is loaded,
 * and through config.xml otherwise. Changes through the REST API send the config to Syncthing
 * afterwards.
 */
// One method per config operation the screens need.
@Suppress("TooManyFunctions")
class ConfigRouter(context: Context) {

    fun interface OnResultListener1<T> {
        fun onResult(t: T)
    }

    private val configXml = ConfigXml(context)

    /** [offline] on the freshly loaded config.xml when Syncthing's REST API is not available. */
    private inline fun <T> route(
        restApi: RestApi?,
        offline: ConfigXml.() -> T,
        online: (RestApi) -> T,
    ): T =
        if (restApi == null || !restApi.isConfigLoaded) {
            configXml.loadConfig()
            configXml.offline()
        } else {
            online(restApi)
        }

    /** Like [route], saving config.xml after the offline change. */
    private inline fun change(
        restApi: RestApi?,
        offline: ConfigXml.() -> Unit,
        online: (RestApi) -> Unit,
    ) =
        route(
            restApi,
            {
                offline()
                saveChanges()
            },
            online,
        )

    fun getFolders(restApi: RestApi?): List<Folder> = route(restApi, { folders }) { it.folders }

    /** The folders shared with [deviceID]. */
    fun getSharedFolders(restApi: RestApi?, deviceID: String?): List<Folder> =
        getFolders(restApi).filter { it.getDevice(deviceID) != null }

    fun addFolder(restApi: RestApi?, folder: Folder) =
        change(restApi, { addFolder(folder) }) { it.addFolder(folder) }

    fun ignoreFolder(restApi: RestApi?, deviceId: String, folderId: String, folderLabel: String?) =
        change(restApi, { ignoreFolder(deviceId, folderId, folderLabel) }) {
            it.ignoreFolder(deviceId, folderId, folderLabel)
        }

    fun updateFolder(restApi: RestApi?, folder: Folder) =
        change(restApi, { updateFolder(folder) }) { it.updateFolder(folder) }

    fun removeFolder(restApi: RestApi?, folderId: String) =
        change(restApi, { removeFolder(folderId) }) { it.removeFolder(folderId) }

    /** Gets the ignore list of the folder. */
    fun getFolderIgnoreList(
        restApi: RestApi?,
        folder: Folder,
        listener: OnResultListener1<FolderIgnoreList>,
    ) =
        route(restApi, { getFolderIgnoreList(folder) { listener.onResult(it) } }) {
            it.getFolderIgnoreList(folder.id) { ignoreList -> listener.onResult(ignoreList) }
        }

    /** Stores the ignore list of the folder. */
    fun postFolderIgnoreList(restApi: RestApi?, folder: Folder, ignore: Array<String>) =
        route(restApi, { postFolderIgnoreList(folder, ignore) }) {
            it.postFolderIgnoreList(folder.id, ignore)
        }

    fun getDevices(restApi: RestApi?, includeLocal: Boolean): List<Device> =
        route(restApi, { getDevices(includeLocal) }) { it.getDevices(includeLocal) }

    fun updateDevice(restApi: RestApi?, device: Device) =
        change(restApi, { updateDevice(device) }) { it.updateDevice(device) }

    fun removeDevice(restApi: RestApi?, deviceID: String) =
        change(restApi, { removeDevice(deviceID) }) { it.removeDevice(deviceID) }

    fun ignoreDevice(
        restApi: RestApi?,
        deviceID: String,
        deviceName: String?,
        deviceAddress: String?,
    ) =
        change(restApi, { ignoreDevice(deviceID, deviceName, deviceAddress) }) {
            it.ignoreDevice(deviceID, deviceName, deviceAddress)
        }

    fun getGui(restApi: RestApi?): Gui = route(restApi, { gui }) { it.gui }

    fun updateGui(restApi: RestApi?, gui: Gui) =
        change(restApi, { updateGui(gui) }) { it.updateGui(gui) }

    fun getOptions(restApi: RestApi?): Options = route(restApi, { options }) { it.options }
}
