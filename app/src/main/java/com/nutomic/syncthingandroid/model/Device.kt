package com.nutomic.syncthingandroid.model

import android.text.TextUtils
import android.util.Log
import com.google.common.io.BaseEncoding
import com.nutomic.syncthingandroid.util.Luhn
import com.nutomic.syncthingandroid.util.splitLikeJava
import java.util.Locale

class Device {
    @JvmField var deviceID: String? = null
    @JvmField var name: String = ""
    @JvmField var addresses: List<String>? = null
    @JvmField var allowedNetworks: List<String>? = null
    @JvmField var compression: String = "metadata"
    @JvmField var certName: String? = null
    @JvmField var introducedBy: String = ""
    @JvmField var introducer: Boolean = false
    @JvmField var paused: Boolean = false
    @JvmField var ignoredFolders: List<IgnoredFolder>? = null
    @JvmField var autoAcceptFolders: Boolean = false
    @JvmField var maxRecvKbps: Int? = 0
    @JvmField var maxSendKbps: Int? = 0

    // Since v1.12.0
    // See https://github.com/syncthing/syncthing/pull/7055
    @JvmField var untrusted: Boolean = false

    // Since v1.25.0
    @JvmField var numConnections: Int? = 0

    /*
     * Relevant fields for the Folder "shared-with-device" model, handled by
     * ConfigRouter.updateFolder and ConfigXml.updateFolder: deviceID, introducedBy.
     * Example tag:
     *  <folder ...>
     *      <device id="[DEVICE_SHARING_THAT_FOLDER_WITH_US]"
     *          introducedBy="[INTRODUCER_DEVICE_THAT_TOLD_US_ABOUT_THE_FOLDER_OR_EMPTY_STRING]"></device>
     *  </folder>
     */

    /** The device name, or the first characters of the ID if the name is empty. */
    val displayName: String
        get() =
            when {
                !TextUtils.isEmpty(name) -> name
                TextUtils.isEmpty(deviceID) -> ""
                else -> deviceID.orEmpty().substring(0, SHORT_ID_LENGTH)
            }

    /**
     * Whether the device ID is correctly formatted. See
     * https://github.com/syncthing/syncthing/blob/master/lib/protocol/deviceid.go for how syncthing
     * validates device IDs.
     */
    fun checkDeviceID(): Boolean {
        val id =
            checkNotNull(deviceID)
                // Trim "="
                .replace("=", "")
                .uppercase(Locale.ROOT)
                // untypeoify
                .replace("1", "I")
                .replace("0", "O")
                .replace("8", "B")
                // unchunkify
                .replace("-", "")
                .replace(" ", "")
        return when (id.length) {
            LUHNIFIED_LENGTH -> unluhnify(id)?.let(::isBase32) ?: false
            BASE32_LENGTH -> isBase32(id)
            else -> false
        }
    }

    /** Strips and checks the Luhn check digits; null when one is wrong or invalid. */
    private fun unluhnify(id: String): String? {
        val bytesIn = id.toByteArray()
        val res = ByteArray(BASE32_LENGTH)
        for (i in 0 until GROUPS) {
            val p = bytesIn.copyOfRange(i * (GROUP_LENGTH + 1), (i + 1) * (GROUP_LENGTH + 1) - 1)
            System.arraycopy(p, 0, res, i * GROUP_LENGTH, GROUP_LENGTH)
            // Generate check digit.
            val checkRune = Luhn().generate(p) ?: return null
            val checkDigitIndex = (i + 1) * (GROUP_LENGTH + 1) - 1
            if (id.substring(checkDigitIndex, checkDigitIndex + 1) != checkRune) {
                return null
            }
        }
        return String(res)
    }

    private fun isBase32(id: String): Boolean = BaseEncoding.base32().canDecode("$id====")

    /**
     * Whether the addresses are correctly formatted. See
     * https://docs.syncthing.net/users/config.html#device-element for what is correct. It catches
     * the most common mistakes, not all of them.
     */
    fun checkDeviceAddresses(): Boolean {
        if (!testCheckDeviceAddress()) {
            Log.e(TAG, "checkDeviceAddresses: testCheckDeviceAddress unit test failed")
            return false
        }
        val addresses = addresses ?: return false
        return addresses.all(::checkDeviceAddress)
    }

    private fun checkDeviceAddress(address: String): Boolean {
        if (address == "dynamic") {
            return true
        }
        if (
            !address.matches(Regex("^tcp([46])?://.*$")) &&
                !address.matches(Regex("^relay://.*$")) &&
                !address.matches(Regex("^quic([46])?://.*$"))
        ) {
            return false
        }
        // Separate protocol from address and port. More than two parts means the protocol is given
        // more than once ("tcp://tcp://"); one part means there is nothing after the protocol.
        val addressSplit = address.splitLikeJava("://")
        if (addressSplit.size != 2) {
            return false
        }
        val (protocol, rest) = addressSplit
        return when {
            protocol.matches(Regex("^tcp.*$")) -> checkHostAndPort(rest, portPart = LAST_PART)
            protocol.matches(Regex("^relay.*$")) -> checkHostAndPort(rest, portPart = 1)
            protocol.matches(Regex("^quic.*$")) -> checkHostAndPort(rest, portPart = LAST_PART)
            else -> false
        }
    }

    /**
     * Checks "host[:port][/...]": no trailing ":", no empty host before a port, and a port in
     * 1..65535. [portPart] is the index of the ":"-separated part holding the port, or [LAST_PART].
     */
    private fun checkHostAndPort(address: String, portPart: Int): Boolean {
        // "tcp://myserver:" or "tcp://[::1]:"
        if (address.endsWith(":") || address.endsWith("]:")) {
            return false
        }
        val hostnamePortSplit = address.splitLikeJava(":")
        if (hostnamePortSplit.size <= 1) {
            return true
        }
        // Empty hostname or IP address before the port: "tcp://:4000"
        if (TextUtils.isEmpty(hostnamePortSplit[0])) {
            return false
        }
        val index = if (portPart == LAST_PART) hostnamePortSplit.size - 1 else portPart
        val potentialPort = hostnamePortSplit[index].splitLikeJava("/")[0]
        // Ending with "]" means the end of an IPv6 address, not a port.
        if (potentialPort.endsWith("]")) {
            return true
        }
        val port = potentialPort.toIntOrNull() ?: 0
        return port in 1..MAX_PORT
    }

    private fun testCheckDeviceAddress(): Boolean =
        VALID_ADDRESSES.all(::checkDeviceAddress) && INVALID_ADDRESSES.none(::checkDeviceAddress)

    private companion object {
        const val TAG = "Device"
        const val GROUPS = 4
        const val GROUP_LENGTH = 13
        const val BASE32_LENGTH = GROUPS * GROUP_LENGTH
        const val LUHNIFIED_LENGTH = GROUPS * (GROUP_LENGTH + 1)
        const val MAX_PORT = 65535
        const val LAST_PART = -1

        val VALID_ADDRESSES =
            listOf(
                "tcp://127.0.0.1:4000",
                "tcp4://127.0.0.1:4000",
                "tcp6://127.0.0.1:4000",
                "tcp4://127.0.0.1",
                "tcp://[2001:db8::23:42]",
                "tcp://[2001:db8::23:42]:12345",
                "tcp://myserver",
                "tcp://myserver:12345",
                "relay://stlocal:22067/?id=ID-REDACTED&pingInterval=30s&networkTimeout=2m0s" +
                    "&sessionLimitBps=0&globalLimitBps=0&statusAddr=:22070&providedBy=REDACTED",
                "relay://stlocal:22067",
                "quic://127.0.0.1",
                "quic://127.0.0.1:24000",
                "quic4://127.0.0.1:24000",
                "quic6://127.0.0.1:24000",
            )
        val INVALID_ADDRESSES = listOf("tcp://myserver:", "tcp8://127.0.0.1", "udp4://127.0.0.1")
    }
}
