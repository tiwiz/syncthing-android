package com.nutomic.syncthingandroid.util

import android.text.Editable
import android.text.TextWatcher

/** A [TextWatcher] that does nothing; override only the callbacks you need. */
open class TextWatcherAdapter : TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

    override fun afterTextChanged(s: Editable?) = Unit
}
