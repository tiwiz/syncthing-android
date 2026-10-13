package com.nutomic.syncthingandroid.model

import android.text.TextUtils

class RemoteIgnoredDevice {
    @JvmField var time: String = ""
    @JvmField var deviceID: String = ""
    @JvmField var name: String = ""
    @JvmField var address: String = ""

    /** The device name, or the first characters of the ID if the name is empty. */
    val displayName: String
        get() = if (TextUtils.isEmpty(name)) deviceID.substring(0, SHORT_ID_LENGTH) else name
}
