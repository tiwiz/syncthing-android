package com.nutomic.syncthingandroid.views

import androidx.annotation.ColorRes
import com.nutomic.syncthingandroid.R

/** What kind of state a folder or device is in: picks its color, and later its shape and icon. */
enum class StateTone(@param:ColorRes @get:ColorRes val textColor: Int) {
    /** Up to date. */
    OK(R.color.text_green),
    /** Syncing, scanning, cleaning. */
    BUSY(R.color.text_blue),
    /** Waiting for its turn, or not shared with anyone. */
    ATTENTION(R.color.text_orange),
    PAUSED(R.color.text_purple),
    /** Failed items, out of sync, errors. */
    ERROR(R.color.text_red),
    /** Disconnected, or Syncthing not running. */
    OFFLINE(R.color.text_red),
}
