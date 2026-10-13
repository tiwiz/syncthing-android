package com.nutomic.syncthingandroid.util

import android.content.Context
import com.nutomic.syncthingandroid.R

/**
 * Device compression attribute helper. This unifies operations between string values as expected by
 * Syncthing with string values as displayed to the user and int ordinals as expected by the dialog
 * click interface.
 */
enum class Compression(val index: Int) {
    NONE(0),
    METADATA(1),
    ALWAYS(2);

    fun getValue(context: Context): String =
        context.resources.getStringArray(R.array.compress_values)[index]

    fun getTitle(context: Context): String =
        context.resources.getStringArray(R.array.compress_entries)[index]

    companion object {
        @JvmStatic
        fun fromIndex(index: Int): Compression =
            when (index) {
                NONE.index -> NONE
                ALWAYS.index -> ALWAYS
                else -> METADATA
            }

        /** The compression with this Syncthing value; METADATA, Syncthing's default, if unknown. */
        @JvmStatic
        fun fromValue(context: Context, value: String?): Compression =
            fromIndex(context.resources.getStringArray(R.array.compress_values).lastIndexOf(value))
    }
}
