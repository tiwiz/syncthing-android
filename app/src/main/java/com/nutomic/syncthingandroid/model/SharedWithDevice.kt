package com.nutomic.syncthingandroid.model

import android.text.TextUtils

class SharedWithDevice {
    @JvmField var deviceID: String? = null
    @JvmField var introducedBy: String = ""
    // Since v1.12.0
    // See https://github.com/syncthing/syncthing/pull/7055
    @JvmField var encryptionPassword: String = ""

    /** The first characters of the device ID, or "" without an ID. */
    val displayName: String
        get() =
            if (TextUtils.isEmpty(deviceID)) ""
            else deviceID.orEmpty().substring(0, SHORT_ID_LENGTH)
}
