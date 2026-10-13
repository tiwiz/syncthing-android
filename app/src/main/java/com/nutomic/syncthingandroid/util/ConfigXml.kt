package com.nutomic.syncthingandroid.util

import android.content.Context
import android.os.Build
import android.os.Environment
import android.text.TextUtils
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.model.Device
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.model.FolderIgnoreList
import com.nutomic.syncthingandroid.model.Gui
import com.nutomic.syncthingandroid.model.Options
import com.nutomic.syncthingandroid.service.AppPrefs
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.SyncthingRunnable
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.MalformedURLException
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerException
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.mindrot.jbcrypt.BCrypt
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException

/**
 * Syncthing's config.xml, read and written directly while Syncthing is not running (ConfigRouter
 * goes through the REST API otherwise). The model conversions are in ConfigXmlModels.kt.
 */
// One method per config operation ConfigRouter and the screens need.
@Suppress("TooManyFunctions")
class ConfigXml(private val context: Context) {

    class OpenConfigException : RuntimeException()

    fun interface OnResultListener1<T> {
        fun onResult(t: T)
    }

    private val enableVerboseLog = AppPrefs.getPrefVerboseLog(context)
    private val configFile: File = Constants.getConfigFile(context)
    private lateinit var config: Document

    private val root: Element
        get() = config.documentElement

    @Throws(OpenConfigException::class)
    fun loadConfig() {
        parseConfig()
        updateIfNeeded()
    }

    /**
     * Generates new keys and a config. This should run in the background as it can cause a full CPU
     * load for more than 30 seconds on older phone hardware.
     */
    @Throws(OpenConfigException::class, SyncthingRunnable.ExecutableNotFoundException::class)
    fun generateConfig() {
        Log.i(TAG, "(Re)Generating keys and config.")
        SyncthingRunnable(context, SyncthingRunnable.Command.generate).run(true)
        parseConfig()
        var changed = false

        // Set local device name.
        Log.i(TAG, "Starting syncthing to retrieve local device id.")
        val localDeviceID = getLocalDeviceIDandStoreToPref()
        if (!TextUtils.isEmpty(localDeviceID)) {
            changed = changeLocalDeviceName(localDeviceID) || changed
        }

        // Change default folder section: versions go to the trash can for 14 days.
        root.firstElement("defaults")?.firstElement("folder")?.firstElement("versioning")?.let {
            it.setAttribute("type", "trashcan")
            val elementParam = config.createElement("param")
            it.appendChild(elementParam)
            elementParam.setAttribute("key", "cleanoutDays")
            elementParam.setAttribute("val", DEFAULT_CLEANOUT_DAYS.toString())
            changed = true
        }

        val gui = guiElement ?: throw OpenConfigException()
        // Set user to "syncthing"
        changed = gui.setChildText("user", "syncthing") || changed
        // Initialize password to the API key
        changed =
            gui.setChildText(
                "password",
                BCrypt.hashpw(apiKey, BCrypt.gensalt(BCRYPT_LOG_ROUNDS)),
            ) || changed
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putString(Constants.PREF_WEBUI_PASSWORD, apiKey)
        }

        // Allow debug and release to run in parallel for testing purposes.
        if (Constants.isDebuggable(context)) {
            // Set alternative gui listen port.
            changed = gui.setChildText("address", "127.0.0.1:8385") || changed
            // Set alternative data listen port.
            root.firstElement("options")?.let {
                changed =
                    it.setChildTexts(
                        "listenAddress",
                        arrayOf("tcp://:22001", "dynamic+https://relays.syncthing.net/endpoint"),
                    ) || changed
            }
        }

        // Save changes if we made any.
        if (changed) {
            saveChanges()
        }
    }

    private fun getLocalDeviceIDfromPref(): String {
        var localDeviceID =
            PreferenceManager.getDefaultSharedPreferences(context)
                .getString(Constants.PREF_LOCAL_DEVICE_ID, "")
                .orEmpty()
        if (TextUtils.isEmpty(localDeviceID)) {
            Log.d(
                TAG,
                "getLocalDeviceIDfromPref: Local device ID unavailable, trying to retrieve it from syncthing ...",
            )
            try {
                localDeviceID = getLocalDeviceIDandStoreToPref()
            } catch (_: SyncthingRunnable.ExecutableNotFoundException) {
                Log.e(TAG, "getLocalDeviceIDfromPref: Failed to execute syncthing core")
            }
            if (TextUtils.isEmpty(localDeviceID)) {
                Log.e(TAG, "getLocalDeviceIDfromPref: Local device ID unavailable")
            }
        }
        return localDeviceID
    }

    @Throws(SyncthingRunnable.ExecutableNotFoundException::class)
    private fun getLocalDeviceIDandStoreToPref(): String {
        val logOutput = SyncthingRunnable(context, SyncthingRunnable.Command.deviceid).run(true)
        val localDeviceID = logOutput.replace("\n", "")
        // Verify that local device ID is correctly formatted.
        if (!Device().apply { deviceID = localDeviceID }.checkDeviceID()) {
            Log.w(
                TAG,
                "getLocalDeviceIDandStoreToPref: Syncthing core returned a bad formatted device ID \"$localDeviceID\"",
            )
            return ""
        }
        // Store local device ID to pref. This saves us expensive calls to the syncthing binary if
        // we
        // need it later.
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putString(Constants.PREF_LOCAL_DEVICE_ID, localDeviceID)
        }
        Log.d(TAG, "getLocalDeviceIDandStoreToPref: Cached local device ID \"$localDeviceID\"")
        return localDeviceID
    }

    private fun parseConfig() {
        if (!configFile.canRead()) {
            Log.w(TAG, "Failed to open config file '$configFile'")
            throw OpenConfigException()
        }
        try {
            FileInputStream(configFile).use { inputStream ->
                val inputSource = InputSource(inputStream.reader(Charsets.UTF_8))
                inputSource.encoding = "UTF-8"
                config =
                    DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(inputSource)
            }
        } catch (e: SAXException) {
            failParse(e)
        } catch (e: ParserConfigurationException) {
            failParse(e)
        } catch (e: IOException) {
            failParse(e)
        }
    }

    private fun failParse(e: Exception): Nothing {
        Log.w(TAG, "Failed to parse config file '$configFile'", e)
        throw OpenConfigException()
    }

    val webGuiUrl: URL
        get() {
            val urlProtocol = if (Constants.osSupportsTLS12()) "https" else "http"
            try {
                return URL("$urlProtocol://" + guiAddress)
            } catch (e: MalformedURLException) {
                throw IllegalStateException("Failed to parse web interface URL", e)
            }
        }

    val webGuiBindPort: Int
        get() =
            try {
                Integer.parseInt(Gui().apply { address = guiAddress }.bindPort)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // No GUI element or address, or no port in it: the default port.
                Log.w(TAG, "getWebGuiBindPort: Failed with exception: ", e)
                Constants.DEFAULT_WEBGUI_TCP_PORT
            }

    private val guiAddress: String
        get() = checkNotNull(guiElement).getElementsByTagName("address").item(0).textContent

    val apiKey: String
        get() = checkNotNull(guiElement).getElementsByTagName("apikey").item(0).textContent

    val webUIUsername: String
        get() = checkNotNull(guiElement).getElementsByTagName("user").item(0)?.textContent.orEmpty()

    val webUIPassword: String
        get() =
            PreferenceManager.getDefaultSharedPreferences(context)
                .getString(Constants.PREF_WEBUI_PASSWORD, "")
                .orEmpty()

    /**
     * Updates the config file. Sets ignorePerms flag to true on every folder, force enables TLS,
     * sets the username/password.
     */
    private fun updateIfNeeded() {
        // Perform one-time migration tasks when coming from an older config version.
        var changed = migrateSyncthingOptions()

        changed = normalizeFolders() || changed
        changed = forceGuiTls() || changed

        // Section - options
        val options = root.firstElement("options") ?: throw OpenConfigException()
        changed = dismissKnownNotifications(options) || changed

        // Disable "startBrowser": it applies to desktop environments and cannot start a mobile
        // browser app.
        changed = options.setChildText("startBrowser", Options().startBrowser) || changed

        // Disable Syncthing's NAT feature because it causes kernel oops on some buggy kernels.
        if (Constants.osHasKernelBugIssue505() && options.setChildText("natEnabled", false)) {
            Log.d(TAG, "Disabling NAT option because a buggy kernel was detected.")
            changed = true
        }

        // Add the "Syncthing Camera" folder if the user consented to use the feature.
        if (
            PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(Constants.PREF_ENABLE_SYNCTHING_CAMERA, false)
        ) {
            changed = addSyncthingCameraFolder() || changed
        }

        // Save changes if we made any.
        if (changed) {
            saveChanges()
        }
    }

    /** Sets ignorePerms and one hasher on every folder. Returns if changes have been made. */
    private fun normalizeFolders(): Boolean {
        var changed = false
        for (r in root.descendantElements("folder")) {
            // Set ignorePerms attribute.
            if (
                !r.hasAttribute("ignorePerms") ||
                    !java.lang.Boolean.parseBoolean(r.getAttribute("ignorePerms"))
            ) {
                Log.i(TAG, "Set 'ignorePerms' on folder " + r.getAttribute("id"))
                r.setAttribute("ignorePerms", true.toString())
                changed = true
            }
            // Set 'hashers' on the given folder.
            changed = r.setChildText("hashers", "1") || changed
        }
        return changed
    }

    /**
     * Platform-specific: forces REST API and Web UI access to use TLS 1.2 or not. Returns if
     * changes have been made.
     */
    private fun forceGuiTls(): Boolean {
        val gui = guiElement ?: throw OpenConfigException()
        val forceHttps = Constants.osSupportsTLS12()
        if (
            !gui.hasAttribute("tls") ||
                java.lang.Boolean.parseBoolean(gui.getAttribute("tls")) != forceHttps
        ) {
            gui.setAttribute("tls", forceHttps.toString())
            return true
        }
        return false
    }

    /**
     * Dismisses notifications that do not apply on Android, like "fsWatcherNotification" (see
     * https://github.com/syncthing/syncthing-android/pull/1051). Iterates the live child list as
     * the Java original did: removing a node skips the node after it.
     */
    private fun dismissKnownNotifications(options: Element): Boolean {
        var changed = false
        val childNodes = options.childNodes
        var i = 0
        while (i < childNodes.length) {
            val node = childNodes.item(i)
            if (node.nodeName == "unackedNotificationID") {
                val notificationType = node.contentOr("")
                if (notificationType in DISMISSED_NOTIFICATIONS) {
                    Log.i(TAG, "Remove found unackedNotificationID '$notificationType'.")
                    options.removeChild(node)
                    changed = true
                }
            }
            i++
        }
        return changed
    }

    /**
     * Updates syncthing options to a version specific target setting in the config file. Used for
     * one-time config migration from a lower syncthing version to the current version. Enables
     * filesystem watcher. Returns if changes to the config have been made.
     */
    private fun migrateSyncthingOptions(): Boolean {
        val defaultFolder = Folder()
        // Read existing config version
        val oldConfigVersion = root.attrOr("version", 0)
        if (oldConfigVersion != CONFIG_VERSION_FS_WATCHER - 1) {
            return false
        }
        // fsWatcher transition
        Log.i(TAG, "Migrating config version $oldConfigVersion to $CONFIG_VERSION_FS_WATCHER ...")
        // Enable fsWatcher for all folders
        for (r in root.descendantElements("folder")) {
            // Enable "fsWatcherEnabled" attribute and set default delay.
            Log.i(
                TAG,
                "Set 'fsWatcherEnabled', 'fsWatcherDelayS' on folder " + r.getAttribute("id"),
            )
            r.setAttribute("fsWatcherEnabled", defaultFolder.fsWatcherEnabled.toString())
            r.setAttribute("fsWatcherDelayS", defaultFolder.fsWatcherDelayS.toString())
        }
        // Setting the version after the manual migration prevents "unackedNotificationID" getting
        // populated with the fsWatcher GUI notification.
        root.setAttribute("version", CONFIG_VERSION_FS_WATCHER.toString())
        Log.i(
            TAG,
            "Old config version was $oldConfigVersion, new config version is $CONFIG_VERSION_FS_WATCHER",
        )
        return true
    }

    /** The folders, sorted by label (or ID without one), not counting the defaults' template. */
    val folders: List<Folder>
        get() {
            val localDeviceID = getLocalDeviceIDfromPref()
            // Prevent enumerating "<folder>" tags below "<defaults>" nodes.
            return root
                .childElements("folder")
                .map { readFolder(it, localDeviceID) }
                .sortedWith(FOLDERS_COMPARATOR)
        }

    fun addFolder(folder: Folder) {
        Log.d(TAG, "addFolder: folder.id=" + folder.id)
        val elementFolder = config.createElement("folder")
        root.appendChild(elementFolder)
        elementFolder.setAttribute("id", folder.id)
        updateFolder(folder)
    }

    fun updateFolder(folder: Folder) {
        val localDeviceID = getLocalDeviceIDfromPref()
        root
            .descendantElements("folder")
            .firstOrNull { checkNotNull(folder.id) == it.attrOr("id", "") }
            ?.let { writeFolder(it, folder, localDeviceID) }
    }

    fun removeFolder(folderId: String) {
        root
            .descendantElements("folder")
            .lastOrNull { folderId == it.attrOr("id", "") }
            ?.let {
                // Found folder node to remove.
                Log.d(TAG, "removeFolder: Removing folder node, folderId=$folderId")
                (it.parentNode as Element).removeIndented(it)
            }
    }

    fun setFolderPause(folderId: String?, paused: Boolean?) {
        root
            .descendantElements("folder")
            .firstOrNull { it.attrOr("id", "") == folderId }
            ?.setChildText("paused", paused)
    }

    /** Reads the ignore list from the folder's .stignore file; empty without one. */
    fun getFolderIgnoreList(folder: Folder, listener: OnResultListener1<FolderIgnoreList>) {
        val folderIgnoreList = FolderIgnoreList()
        val file = File(folder.path, Constants.FILENAME_STIGNORE)
        try {
            if (file.exists()) {
                val data = ByteArray(file.length().toInt())
                FileInputStream(file).use { it.read(data) }
                folderIgnoreList.ignore =
                    String(data, Charsets.UTF_8).splitLikeJava("\n").toTypedArray()
            } else {
                // Don't fail: the file might be expectedly missing when users didn't set ignores in
                // the past storyline of that folder.
                Log.w(TAG, "getFolderIgnoreList: File missing $file")
            }
        } catch (e: IOException) {
            Log.e(
                TAG,
                "getFolderIgnoreList: Failed to read '${folder.path}/${Constants.FILENAME_STIGNORE}' #1",
                e,
            )
        }
        listener.onResult(folderIgnoreList)
    }

    /** Stores the ignore list in the folder's .stignore file. */
    fun postFolderIgnoreList(folder: Folder, ignore: Array<String>) {
        try {
            val file = File(folder.path, Constants.FILENAME_STIGNORE)
            if (!file.exists()) {
                file.createNewFile()
            }
            FileOutputStream(file).use {
                it.write(ignore.joinToString("\n").toByteArray(Charsets.UTF_8))
                it.flush()
            }
        } catch (e: IOException) {
            // This will happen on external storage folders which exist outside the
            // "/Android/data/[package_name]/files" folder on Android 5+.
            Log.w(
                TAG,
                "postFolderIgnoreList: Failed to write '${folder.path}/${Constants.FILENAME_STIGNORE}' #1",
                e,
            )
        }
    }

    /** The devices, sorted by name (or ID without one); the local one only if [includeLocal]. */
    fun getDevices(includeLocal: Boolean): List<Device> {
        val localDeviceID = getLocalDeviceIDfromPref()
        // Prevent enumerating "<device>" tags below "<defaults>", "<folder>" nodes.
        return root
            .childElements("device")
            .map(::readDevice)
            .filter { device ->
                val isLocalDevice =
                    !TextUtils.isEmpty(device.deviceID) && device.deviceID == localDeviceID
                includeLocal || !isLocalDevice
            }
            .sortedWith(DEVICES_COMPARATOR)
    }

    /** Adds or updates a device identified by its device ID. */
    fun updateDevice(device: Device) {
        val deviceID = checkNotNull(device.deviceID)
        // Prevent enumerating "<device>" tags below "<folder>" nodes.
        val existing = root.childElements("device").firstOrNull { deviceID == it.attrOr("id", "") }
        val element =
            existing
                ?: config.createElement("device").also {
                    // If the device does not exist in config, add it.
                    Log.d(
                        TAG,
                        "updateDevice: [addDevice] Adding deviceID='$deviceID' to config ...",
                    )
                    root.appendChild(it)
                    it.setAttribute("id", deviceID)
                }
        writeDevice(element, device)
    }

    fun removeDevice(deviceID: String) {
        // Prevent enumerating "<device>" tags below "<folder>" nodes.
        root
            .childElements("device")
            .firstOrNull { deviceID == it.attrOr("id", "") }
            ?.let {
                Log.d(TAG, "removeDevice: Removing device node, deviceID=$deviceID")
                (it.parentNode as Element).removeIndented(it)
            }
        // Unshare the device from every folder, like Syncthing does for devices that no longer
        // exist.
        for (elementFolder in root.descendantElements("folder")) {
            for (elementDevice in elementFolder.descendantElements("device").asReversed()) {
                if (deviceID == elementDevice.attrOr("id", "")) {
                    Log.d(
                        TAG,
                        "removeDevice: Unsharing folder ${elementFolder.attrOr("id", "")} from deviceID=$deviceID",
                    )
                    (elementDevice.parentNode as Element).removeIndented(elementDevice)
                }
            }
        }
    }

    /**
     * Permanently ignores a device, like RestApi.ignoreDevice does while Syncthing is running. Adds
     * a "remoteIgnoredDevice" node unless the device is already ignored.
     */
    fun ignoreDevice(deviceID: String, deviceName: String?, deviceAddress: String?) {
        if (
            root.descendantElements("remoteIgnoredDevice").any { deviceID == it.attrOr("id", "") }
        ) {
            Log.d(TAG, "ignoreDevice: Device already ignored [$deviceID]")
            return
        }
        val elementIgnoredDevice = config.createElement("remoteIgnoredDevice")
        elementIgnoredDevice.setAttribute("time", getLocalZonedDateTime())
        elementIgnoredDevice.setAttribute("id", deviceID)
        elementIgnoredDevice.setAttribute("name", deviceName)
        elementIgnoredDevice.setAttribute("address", deviceAddress)
        root.appendChild(elementIgnoredDevice)
        Log.d(TAG, "ignoreDevice: Ignored device [$deviceID]")
    }

    /**
     * Permanently ignores a folder offered by a device, like RestApi.ignoreFolder does while
     * Syncthing is running. Adds an "ignoredFolder" node to the device unless the folder is already
     * ignored. Does nothing for an unknown device.
     */
    fun ignoreFolder(deviceID: String, folderID: String, folderLabel: String?) {
        // Prevent enumerating "<device>" tags below "<folder>" nodes.
        val elementDevice =
            root.childElements("device").firstOrNull { deviceID == it.attrOr("id", "") }
        if (elementDevice == null) {
            Log.w(TAG, "ignoreFolder: Unknown device [$deviceID]")
            return
        }
        if (
            elementDevice.descendantElements("ignoredFolder").any {
                folderID == it.attrOr("id", "")
            }
        ) {
            Log.d(TAG, "ignoreFolder: Folder [$folderID] already ignored on device [$deviceID]")
            return
        }
        val elementIgnoredFolder = config.createElement("ignoredFolder")
        elementIgnoredFolder.setAttribute("time", getLocalZonedDateTime())
        elementIgnoredFolder.setAttribute("id", folderID)
        elementIgnoredFolder.setAttribute("label", folderLabel)
        elementDevice.appendChild(elementIgnoredFolder)
        Log.d(TAG, "ignoreFolder: Ignored folder [$folderID] announced by device [$deviceID]")
    }

    val gui: Gui
        get() {
            val elementGui = root.firstElement("gui")
            if (elementGui == null) {
                Log.e(TAG, "getGui: elementGui == null. Returning defaults.")
                return Gui()
            }
            return readGui(elementGui)
        }

    fun updateGui(gui: Gui) {
        val elementGui = root.firstElement("gui")
        if (elementGui == null) {
            Log.e(TAG, "updateGui: elementGui == null")
            return
        }
        writeGui(elementGui, gui)
    }

    val options: Options
        get() {
            val elementOptions = root.firstElement("options")
            if (elementOptions == null) {
                Log.e(TAG, "getOptions: elementOptions == null. Returning defaults.")
                return Options()
            }
            return readOptions(elementOptions)
        }

    fun setDevicePause(deviceId: String?, paused: Boolean?) {
        // Prevent enumerating "<device>" tags below "<folder>" nodes.
        root
            .childElements("device")
            .firstOrNull { it.attrOr("id", "") == deviceId }
            ?.setChildText("paused", paused)
    }

    private val guiElement: Element?
        get() = root.firstElement("gui")

    /**
     * Sets the device model name as device name for Syncthing, on the top-level device with the
     * local device ID only (not the ones nested in folders). Returns if changes have been made.
     */
    private fun changeLocalDeviceName(localDeviceID: String): Boolean {
        val device =
            root.childElements("device").firstOrNull { it.getAttribute("id") == localDeviceID }
        if (device == null) {
            return false
        }
        Log.i(TAG, "changeLocalDeviceName: Rename device ID $localDeviceID to ${Build.MODEL}")
        device.setAttribute("name", Build.MODEL)
        return true
    }

    /**
     * Adds a new folder pointing to the app-specific "Syncthing Camera" directory if it hasn't been
     * added to the config yet. Returns if changes to the config have been made.
     */
    private fun addSyncthingCameraFolder(): Boolean {
        val folderAlreadyPresentInConfig =
            root.descendantElements("folder").any {
                val folderId = it.attrOr("id", "")
                !TextUtils.isEmpty(folderId) && folderId == Constants.syncthingCameraFolderId
            }
        if (folderAlreadyPresentInConfig) {
            return false
        }
        // App specific directory, e.g. "/storage/emulated/0/Android/media/[PACKAGE_NAME]/Pictures".
        val storageDir =
            FileUtils.getExternalFilesDir(
                context,
                FileUtils.ExternalStorageDirType.INT_MEDIA,
                Environment.DIRECTORY_PICTURES,
            )
        if (storageDir == null) {
            Log.e(TAG, "addSyncthingCameraFolder: storageDir == null")
            return false
        }
        val folder =
            Folder().apply {
                minDiskFree = Folder.MinDiskFree()
                id = Constants.syncthingCameraFolderId
                label = context.getString(R.string.default_syncthing_camera_folder_label)
                path = storageDir.absolutePath
                versioning =
                    Folder.Versioning().apply {
                        type = "trashcan"
                        params = hashMapOf("cleanoutDays" to DEFAULT_CLEANOUT_DAYS.toString())
                        cleanupIntervalS = CAMERA_CLEANUP_INTERVAL_S
                        fsPath = ""
                        fsType = "basic"
                    }
            }
        logV("addSyncthingCameraFolder: Adding folder to config [${folder.path}]")
        addFolder(folder)
        return true
    }

    /** Writes the config back to the file, through a temporary file. */
    fun saveChanges() {
        if (!configFile.canWrite()) {
            Log.w(TAG, "Failed to save updated config. Cannot change the owner of the config file.")
            return
        }
        Log.i(TAG, "Saving config file")
        val configTempFile = Constants.getConfigTempFile(context)
        try {
            FileOutputStream(configTempFile).use { fileOutputStream ->
                // Write XML header.
                fileOutputStream.write(
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>".toByteArray(Charsets.UTF_8)
                )
                // Prepare Object-to-XML transform.
                val transformer = TransformerFactory.newInstance().newTransformer()
                transformer.setOutputProperty(OutputKeys.METHOD, "xml")
                transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-16")
                transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
                transformer.setOutputProperty(OutputKeys.INDENT, "yes")
                transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4")
                // Output XML body.
                val byteArrayOutputStream = ByteArrayOutputStream()
                transformer.transform(
                    DOMSource(config),
                    StreamResult(OutputStreamWriter(byteArrayOutputStream, Charsets.UTF_8)),
                )
                fileOutputStream.write(byteArrayOutputStream.toByteArray())
            }
        } catch (e: TransformerException) {
            Log.w(TAG, "Failed to transform object to xml and save temporary config file", e)
            return
        } catch (e: IOException) {
            Log.w(TAG, "Failed to save temporary config file", e)
        }
        if (!configTempFile.renameTo(configFile)) {
            Log.w(TAG, "Failed to rename temporary config file to original file")
        }
    }

    private fun logV(logMessage: String) {
        if (enableVerboseLog) {
            Log.v(TAG, logMessage)
        }
    }

    private companion object {
        const val TAG = "ConfigXml"
        const val DEFAULT_CLEANOUT_DAYS = 14
        const val CAMERA_CLEANUP_INTERVAL_S = 3600
        const val BCRYPT_LOG_ROUNDS = 4
        const val CONFIG_VERSION_FS_WATCHER = 28

        val DISMISSED_NOTIFICATIONS =
            setOf(
                "authenticationUserAndPassword",
                "crAutoEnabled",
                "crAutoDisabled",
                "fsWatcherNotification",
            )

        /** Compares devices by name, uses the device ID as fallback if the name is empty. */
        val DEVICES_COMPARATOR = compareBy<Device> { checkNotNull(it.name.ifEmpty { it.deviceID }) }

        /** Compares folders by labels, uses the folder ID as fallback if the label is empty. */
        val FOLDERS_COMPARATOR = compareBy<Folder> { checkNotNull(it.label.ifEmpty { it.id }) }
    }
}
