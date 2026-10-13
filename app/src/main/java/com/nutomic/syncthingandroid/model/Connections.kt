package com.nutomic.syncthingandroid.model

class Connections {
    @JvmField var total: Connection? = null

    // Named after the JSON field Syncthing sends.
    @Suppress("MemberNameEqualsClassName")
    @JvmField
    var connections: Map<String, Connection>? = null
}
