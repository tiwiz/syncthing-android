package com.nutomic.syncthingandroid.model

import kotlin.math.max

class Connection {
    @JvmField var address: String = ""
    @JvmField var at: String = "" // "0001-01-01T00:00:00Z"
    @JvmField var clientVersion: String = ""
    @JvmField var connected: Boolean = false
    @JvmField var inBytesTotal: Long = 0
    @JvmField var outBytesTotal: Long = 0
    @JvmField var paused: Boolean = false
    @JvmField var type: String = ""

    // These fields are not sent from Syncthing. They are populated by [setTransferRate].
    @JvmField var inBits: Long = 0
    @JvmField var outBits: Long = 0

    fun setTransferRate(previous: Connection, msElapsed: Long) {
        val secondsElapsed = msElapsed / MILLIS_PER_SECOND
        val inBytes = BITS_PER_BYTE * (inBytesTotal - previous.inBytesTotal) / secondsElapsed
        val outBytes = BITS_PER_BYTE * (outBytesTotal - previous.outBytesTotal) / secondsElapsed
        inBits = max(0, inBytes)
        outBits = max(0, outBytes)
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000
        const val BITS_PER_BYTE = 8
    }
}
