package com.nutomic.syncthingandroid.model

import android.text.TextUtils

class IgnoredFolder {
    @JvmField var id: String = ""
    @JvmField var label: String = ""
    @JvmField var time: String = ""

    /** The folder label, or the first characters of the ID if the label is empty. */
    val displayLabel: String
        get() = if (TextUtils.isEmpty(label)) id.substring(0, SHORT_ID_LENGTH) else label
}
