package com.nutomic.syncthingandroid.util

import android.text.TextUtils
import android.util.Log
import com.nutomic.syncthingandroid.model.Device
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.model.Gui
import com.nutomic.syncthingandroid.model.IgnoredFolder
import com.nutomic.syncthingandroid.model.Options
import com.nutomic.syncthingandroid.model.SharedWithDevice
import com.nutomic.syncthingandroid.service.Constants
import org.w3c.dom.Element

// The model classes to and from config.xml elements, for ConfigXml.

private const val TAG = "ConfigXml"
private const val DEFAULT_CLEANUP_INTERVAL_S = 3600

/** A `<folder>` element; devices other than [localDeviceID] are the ones it is shared with. */
internal fun readFolder(r: Element, localDeviceID: String?): Folder {
    val folder = Folder()
    folder.id = r.attrOr("id", "")
    folder.group = r.attrOr("group", folder.group)
    folder.label = r.attrOr("label", folder.label)
    var path = r.attrOr("path", "")
    if (path.startsWith("~/")) {
        path = path.replaceFirst(Regex("^~"), FileUtils.getSyncthingTildeAbsolutePath())
    }
    folder.path = path
    folder.type = r.attrOr("type", Constants.FOLDER_TYPE_SEND_RECEIVE)
    folder.autoNormalize = r.attrOr("autoNormalize", folder.autoNormalize)
    folder.fsWatcherDelayS = r.attrOr("fsWatcherDelayS", folder.fsWatcherDelayS)
    folder.fsWatcherEnabled = r.attrOr("fsWatcherEnabled", folder.fsWatcherEnabled)
    folder.ignorePerms = r.attrOr("ignorePerms", folder.ignorePerms)
    folder.rescanIntervalS = r.attrOr("rescanIntervalS", folder.rescanIntervalS)
    readFolderOptions(r, folder)
    // <device id="[DEVICE_ID]" introducedBy=""/>
    for (elementDevice in r.descendantElements("device")) {
        val device = SharedWithDevice()
        device.deviceID = elementDevice.attrOr("id", "")
        // Exclude self.
        if (!TextUtils.isEmpty(device.deviceID) && device.deviceID != localDeviceID) {
            device.introducedBy = elementDevice.attrOr("introducedBy", device.introducedBy)
            device.encryptionPassword =
                elementDevice
                    .firstElement("encryptionPassword")
                    .contentOr(device.encryptionPassword)
            folder.addDevice(device)
        }
    }
    // <minDiskFree unit="MB">5</minDiskFree>
    folder.minDiskFree =
        Folder.MinDiskFree().apply {
            r.firstElement("minDiskFree")?.let {
                unit = it.attrOr("unit", unit)
                value = it.contentOr(value)
            }
        }
    folder.versioning = readVersioning(r.firstElement("versioning"))
    return folder
}

private fun readFolderOptions(r: Element, folder: Folder) {
    fun child(name: String) = r.getElementsByTagName(name).item(0)
    folder.copiers = child("copiers").contentOr(folder.copiers)
    folder.hashers = child("hashers").contentOr(folder.hashers)
    folder.order = child("order").contentOrNullable(folder.order)
    folder.paused = child("paused").contentOr(folder.paused)
    folder.ignoreDelete = child("ignoreDelete").contentOr(folder.ignoreDelete)
    folder.copyOwnershipFromParent =
        child("copyOwnershipFromParent").contentOr(checkNotNull(folder.copyOwnershipFromParent))
    folder.modTimeWindowS = child("modTimeWindowS").contentOr(folder.modTimeWindowS)
    folder.blockPullOrder = child("blockPullOrder").contentOr(folder.blockPullOrder)
    folder.disableFsync = child("disableFsync").contentOr(checkNotNull(folder.disableFsync))
    folder.maxConcurrentWrites = child("maxConcurrentWrites").contentOr(folder.maxConcurrentWrites)
    folder.maxConflicts = child("maxConflicts").contentOr(folder.maxConflicts)
    folder.copyRangeMethod = child("copyRangeMethod").contentOr(folder.copyRangeMethod)
    folder.caseSensitiveFS =
        child("caseSensitiveFS").contentOr(checkNotNull(folder.caseSensitiveFS))
    folder.syncOwnership = child("syncOwnership").contentOr(checkNotNull(folder.syncOwnership))
    folder.sendOwnership = child("sendOwnership").contentOr(checkNotNull(folder.sendOwnership))
    folder.syncXattrs = child("syncXattrs").contentOr(checkNotNull(folder.syncXattrs))
    folder.sendXattrs = child("sendXattrs").contentOr(checkNotNull(folder.sendXattrs))
    folder.blockIndexing = child("blockIndexing").contentOr(checkNotNull(folder.blockIndexing))
    folder.filesystemType = child("filesystemType").contentOr(folder.filesystemType)
}

/**
 * `<versioning type="trashcan"><param key="cleanoutDays" val="90"/><cleanupIntervalS>3600
 * </cleanupIntervalS><fsPath/><fsType>basic</fsType></versioning>`; an empty one without it.
 */
private fun readVersioning(elementVersioning: Element?): Folder.Versioning {
    val versioning = Folder.Versioning()
    if (elementVersioning == null) {
        return versioning
    }
    versioning.type = elementVersioning.attrOr("type", "")
    versioning.cleanupIntervalS =
        elementVersioning.firstElement("cleanupIntervalS").contentOr(DEFAULT_CLEANUP_INTERVAL_S)
    versioning.fsPath = elementVersioning.firstElement("fsPath").contentOr("")
    versioning.fsType = elementVersioning.firstElement("fsType").contentOr("basic")
    val params = HashMap<String, String>()
    for (param in elementVersioning.descendantElements("param")) {
        params[param.attrOr("key", "")] = param.attrOr("val", "")
    }
    versioning.params = params
    return versioning
}

/** Writes [folder] into its `<folder>` element; devices other than [localDeviceID] are replaced. */
internal fun writeFolder(r: Element, folder: Folder, localDeviceID: String?) {
    r.setAttribute("group", folder.group)
    r.setAttribute("label", folder.label)
    r.setAttribute("path", folder.path)
    r.setAttribute("type", folder.type)
    r.setAttribute("autoNormalize", folder.autoNormalize.toString())
    r.setAttribute("fsWatcherDelayS", folder.fsWatcherDelayS.toString())
    r.setAttribute("fsWatcherEnabled", folder.fsWatcherEnabled.toString())
    r.setAttribute("ignorePerms", folder.ignorePerms.toString())
    r.setAttribute("rescanIntervalS", folder.rescanIntervalS.toString())
    writeFolderOptions(r, folder)

    // Update devices that share this folder.
    // Pass 1: Remove all devices below that folder in XML except the local device.
    for (elementDevice in r.descendantElements("device").asReversed()) {
        if (elementDevice.attrOr("id", "") != localDeviceID) {
            Log.d(
                TAG,
                "updateFolder: nodeDevices: Removing deviceID=" + elementDevice.attrOr("id", ""),
            )
            r.removeIndented(elementDevice)
        }
    }
    // Pass 2: Add devices below that folder from the POJO model.
    for (device in checkNotNull(folder.sharedWithDevices)) {
        Log.d(TAG, "updateFolder: nodeDevices: Adding deviceID=" + device.deviceID)
        val elementDevice = r.ownerDocument.createElement("device")
        r.appendChild(elementDevice)
        elementDevice.setAttribute("id", device.deviceID)
        elementDevice.setAttribute("introducedBy", device.introducedBy)
        elementDevice.setChildText("encryptionPassword", device.encryptionPassword)
    }
    folder.minDiskFree?.let { writeMinDiskFree(r, it) }
    writeVersioning(r, folder.versioning)
}

private fun writeFolderOptions(r: Element, folder: Folder) {
    r.setChildText("copiers", folder.copiers.toString())
    r.setChildText("hashers", folder.hashers.toString())
    r.setChildText("order", folder.order)
    r.setChildText("paused", folder.paused)
    r.setChildText("ignoreDelete", folder.ignoreDelete)
    r.setChildText("copyOwnershipFromParent", folder.copyOwnershipFromParent)
    r.setChildText("modTimeWindowS", folder.modTimeWindowS.toString())
    r.setChildText("blockPullOrder", folder.blockPullOrder)
    r.setChildText("disableFsync", folder.disableFsync)
    r.setChildText("maxConcurrentWrites", folder.maxConcurrentWrites.toString())
    r.setChildText("maxConflicts", folder.maxConflicts.toString())
    r.setChildText("copyRangeMethod", folder.copyRangeMethod)
    r.setChildText("caseSensitiveFS", folder.caseSensitiveFS)
    r.setChildText("syncOwnership", folder.syncOwnership)
    r.setChildText("sendOwnership", folder.sendOwnership)
    r.setChildText("syncXattrs", folder.syncXattrs)
    r.setChildText("sendXattrs", folder.sendXattrs)
    r.setChildText("blockIndexing", folder.blockIndexing)
    r.setChildText("filesystemType", folder.filesystemType)
}

private fun writeMinDiskFree(r: Element, minDiskFree: Folder.MinDiskFree) {
    // Pass 1: Remove all minDiskFree nodes from XML (usually one)
    r.firstElement("minDiskFree")?.let {
        Log.d(TAG, "updateFolder: nodeMinDiskFree: Removing minDiskFree node")
        r.removeIndented(it)
    }
    // Pass 2: Add minDiskFree node from the POJO model to XML.
    val elementMinDiskFree = r.ownerDocument.createElement("minDiskFree")
    r.appendChild(elementMinDiskFree)
    elementMinDiskFree.setAttribute("unit", minDiskFree.unit)
    r.setChildText("minDiskFree", minDiskFree.value.toString())
}

private fun writeVersioning(r: Element, versioning: Folder.Versioning?) {
    // Pass 1: Remove all versioning nodes from XML (usually one)
    r.firstElement("versioning")?.let {
        Log.d(TAG, "updateFolder: nodeVersioning: Removing versioning node")
        r.removeIndented(it)
    }
    // Pass 2: Add versioning node from the POJO model to XML.
    val elementVersioning = r.ownerDocument.createElement("versioning")
    r.appendChild(elementVersioning)
    // A folder without versioning gets an empty node, like versioning type "".
    if (versioning == null || TextUtils.isEmpty(versioning.type)) {
        return
    }
    elementVersioning.setAttribute("type", versioning.type)
    elementVersioning.setChildText("cleanupIntervalS", versioning.cleanupIntervalS.toString())
    elementVersioning.setChildText("fsPath", versioning.fsPath)
    elementVersioning.setChildText("fsType", versioning.fsType)
    for ((key, value) in versioning.params) {
        Log.d(TAG, "updateFolder: nodeVersioning: Adding param key=$key, val=$value")
        val elementParam = r.ownerDocument.createElement("param")
        elementVersioning.appendChild(elementParam)
        elementParam.setAttribute("key", key)
        elementParam.setAttribute("val", value)
    }
}

/** A top-level `<device>` element. */
internal fun readDevice(r: Element): Device {
    val device = Device()
    fun child(name: String) = r.getElementsByTagName(name).item(0)
    device.compression = r.attrOr("compression", device.compression)
    device.deviceID = r.attrOr("id", "")
    device.introducedBy = r.attrOr("introducedBy", device.introducedBy)
    device.introducer = r.attrOr("introducer", device.introducer)
    device.name = r.attrOr("name", device.name)
    device.autoAcceptFolders = child("autoAcceptFolders").contentOr(device.autoAcceptFolders)
    device.maxRecvKbps = child("maxRecvKbps").contentOr(checkNotNull(device.maxRecvKbps))
    device.maxSendKbps = child("maxSendKbps").contentOr(checkNotNull(device.maxSendKbps))
    device.paused = child("paused").contentOr(device.paused)
    device.untrusted = child("untrusted").contentOr(device.untrusted)
    device.numConnections = child("numConnections").contentOr(checkNotNull(device.numConnections))
    // <address>dynamic</address> <address>tcp4://192.168.1.67:2222</address>
    device.addresses = r.descendantElements("address").map { it.contentOr("") }.toMutableList()
    // <allowedNetwork>192.168.0.0/24</allowedNetwork>
    device.allowedNetworks =
        r.descendantElements("allowedNetwork").map { it.contentOr("") }.toMutableList()
    device.ignoredFolders =
        r.descendantElements("ignoredFolder")
            .map { element ->
                IgnoredFolder().apply {
                    id = element.attrOr("id", id)
                    label = element.attrOr("label", label)
                    time = element.attrOr("time", time)
                }
            }
            .toMutableList()
    return device
}

/** Writes [device] into its top-level `<device>` element. */
internal fun writeDevice(r: Element, device: Device) {
    r.setAttribute("compression", device.compression)
    r.setAttribute("introducedBy", device.introducedBy)
    r.setAttribute("introducer", device.introducer.toString())
    r.setAttribute("name", device.name)
    r.setChildText("autoAcceptFolders", device.autoAcceptFolders)
    r.setChildText("paused", device.paused)
    r.setChildText("untrusted", device.untrusted)
    r.setChildText("numConnections", checkNotNull(device.numConnections).toString())
    replaceTextChildren(r, "address", device.addresses, "updateDevice: nodeAddresses")
    replaceTextChildren(
        r,
        "allowedNetwork",
        device.allowedNetworks,
        "updateDevice: nodeAllowedNetworks",
    )
}

/** Pass 1: removes every [tagName] in [r]; pass 2: adds one per text in [texts], if any. */
private fun replaceTextChildren(r: Element, tagName: String, texts: List<String>?, log: String) {
    for (element in r.descendantElements(tagName).asReversed()) {
        Log.d(TAG, "$log: Removing $tagName=" + element.contentOr(""))
        r.removeIndented(element)
    }
    texts?.forEach { text ->
        Log.d(TAG, "$log: Adding $tagName=$text")
        val element = r.ownerDocument.createElement(tagName)
        r.appendChild(element)
        element.textContent = text
    }
}

/** The `<gui>` element. */
internal fun readGui(elementGui: Element): Gui {
    val gui = Gui()
    fun child(name: String) = elementGui.getElementsByTagName(name).item(0)
    gui.enabled = elementGui.attrOr("enabled", gui.enabled)
    gui.useTLS = elementGui.attrOr("tls", gui.useTLS)
    gui.address = child("address").contentOrNullable(gui.address)
    gui.user = child("user").contentOrNullable(gui.user)
    gui.password = child("password").contentOr("")
    gui.apiKey = child("apikey").contentOr("")
    gui.theme = child("theme").contentOr(gui.theme)
    gui.insecureAdminAccess = child("insecureAdminAccess").contentOr(gui.insecureAdminAccess)
    gui.insecureAllowFrameLoading =
        child("insecureAllowFrameLoading").contentOr(gui.insecureAllowFrameLoading)
    gui.insecureSkipHostCheck = child("insecureSkipHostCheck").contentOr(gui.insecureSkipHostCheck)
    return gui
}

/** Writes [gui] into the `<gui>` element. */
internal fun writeGui(elementGui: Element, gui: Gui) {
    elementGui.setAttribute("enabled", gui.enabled.toString())
    elementGui.setAttribute("tls", gui.useTLS.toString())
    // Syncthing leaves out an empty user (and could an address): unset, they stay out.
    gui.address?.let { elementGui.setChildText("address", it) }
    gui.user?.let { elementGui.setChildText("user", it) }
    elementGui.setChildText("password", gui.password)
    elementGui.setChildText("apikey", gui.apiKey)
    elementGui.setChildText("theme", gui.theme)
    elementGui.setChildText("insecureAdminAccess", gui.insecureAdminAccess)
    elementGui.setChildText("insecureAllowFrameLoading", gui.insecureAllowFrameLoading)
    elementGui.setChildText("insecureSkipHostCheck", gui.insecureSkipHostCheck)
}

/** The `<options>` element. */
internal fun readOptions(elementOptions: Element): Options {
    val o = Options()
    fun child(name: String) = elementOptions.getElementsByTagName(name).item(0)
    o.listenAddresses =
        elementOptions
            .descendantElements("listenAddress")
            .map { it.textContent.trimLikeJava() }
            .filter { it.isNotEmpty() }
            .toTypedArray()
    o.globalAnnounceEnabled = child("globalAnnounceEnabled").contentOr(o.globalAnnounceEnabled)
    o.localAnnounceEnabled = child("localAnnounceEnabled").contentOr(o.localAnnounceEnabled)
    o.localAnnouncePort = child("localAnnouncePort").contentOr(o.localAnnouncePort)
    o.localAnnounceMCAddr = child("localAnnounceMCAddr").contentOr("")
    o.maxSendKbps = child("maxSendKbps").contentOr(o.maxSendKbps)
    o.maxRecvKbps = child("maxRecvKbps").contentOr(o.maxRecvKbps)
    o.reconnectionIntervalS = child("reconnectionIntervalS").contentOr(o.reconnectionIntervalS)
    o.relaysEnabled = child("relaysEnabled").contentOr(o.relaysEnabled)
    o.relayReconnectIntervalM =
        child("relayReconnectIntervalM").contentOr(o.relayReconnectIntervalM)
    o.startBrowser = child("startBrowser").contentOr(o.startBrowser)
    o.natEnabled = child("natEnabled").contentOr(o.natEnabled)
    o.natLeaseMinutes = child("natLeaseMinutes").contentOr(o.natLeaseMinutes)
    o.natRenewalMinutes = child("natRenewalMinutes").contentOr(o.natRenewalMinutes)
    o.natTimeoutSeconds = child("natTimeoutSeconds").contentOr(o.natTimeoutSeconds)
    o.urAccepted = child("urAccepted").contentOr(o.urAccepted)
    o.urUniqueId = child("urUniqueId").contentOr("")
    o.urURL = child("urURL").contentOr(o.urURL)
    o.urPostInsecurely = child("urPostInsecurely").contentOr(o.urPostInsecurely)
    o.urInitialDelayS = child("urInitialDelayS").contentOr(o.urInitialDelayS)
    o.autoUpgradeIntervalH = child("autoUpgradeIntervalH").contentOr(o.autoUpgradeIntervalH)
    o.upgradeToPreReleases = child("upgradeToPreReleases").contentOr(o.upgradeToPreReleases)
    o.keepTemporariesH = child("keepTemporariesH").contentOr(o.keepTemporariesH)
    o.cacheIgnoredFiles = child("cacheIgnoredFiles").contentOr(o.cacheIgnoredFiles)
    o.progressUpdateIntervalS =
        child("progressUpdateIntervalS").contentOr(o.progressUpdateIntervalS)
    o.limitBandwidthInLan = child("limitBandwidthInLan").contentOr(o.limitBandwidthInLan)
    o.releasesURL = child("releasesURL").contentOr(o.releasesURL)
    // alwaysLocalNets
    o.overwriteRemoteDeviceNamesOnConnect =
        child("overwriteRemoteDeviceNamesOnConnect")
            .contentOr(o.overwriteRemoteDeviceNamesOnConnect)
    o.tempIndexMinBlocks = child("tempIndexMinBlocks").contentOr(o.tempIndexMinBlocks)
    o.setLowPriority = child("setLowPriority").contentOr(o.setLowPriority)
    // minHomeDiskFree
    o.maxFolderConcurrency = child("maxFolderConcurrency").contentOr(o.maxFolderConcurrency)
    o.unackedNotificationID = child("unackedNotificationID").contentOr(o.unackedNotificationID)
    o.crURL = child("crashReportingURL").contentOr(o.crURL)
    o.crashReportingEnabled = child("crashReportingEnabled").contentOr(o.crashReportingEnabled)
    o.stunKeepaliveStartS = child("stunKeepaliveStartS").contentOr(o.stunKeepaliveStartS)
    o.stunKeepaliveMinS = child("stunKeepaliveMinS").contentOr(o.stunKeepaliveMinS)
    o.stunServer = child("stunServer").contentOr(o.stunServer)
    o.maxConcurrentIncomingRequestKiB =
        child("maxConcurrentIncomingRequestKiB").contentOr(o.maxConcurrentIncomingRequestKiB)
    o.announceLANAddresses = child("announceLANAddresses").contentOr(o.announceLANAddresses)
    o.sendFullIndexOnUpgrade = child("sendFullIndexOnUpgrade").contentOr(o.sendFullIndexOnUpgrade)
    o.featureFlag = child("featureFlag").contentOr(o.featureFlag)
    o.connectionLimitEnough = child("connectionLimitEnough").contentOr(o.connectionLimitEnough)
    o.connectionLimitMax = child("connectionLimitMax").contentOr(o.connectionLimitMax)
    return o
}
