package com.nutomic.syncthingandroid.views

/** What kind of state a folder or device is in: picks its badge and text color (`ui/StateLook`). */
enum class StateTone {
    /** Up to date. */
    OK,
    /** Syncing, scanning, cleaning. */
    BUSY,
    /** Waiting for its turn, or not shared with anyone. */
    ATTENTION,
    PAUSED,
    /** Failed items, out of sync, errors. */
    ERROR,
    /** Disconnected, or Syncthing not running. */
    OFFLINE,
}
