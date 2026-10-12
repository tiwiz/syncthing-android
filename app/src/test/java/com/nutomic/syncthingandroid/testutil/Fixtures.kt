package com.nutomic.syncthingandroid.testutil

/** Reads files from `src/test/resources/fixtures`. */
object Fixtures {

    fun bytes(path: String): ByteArray =
        checkNotNull(Fixtures::class.java.getResourceAsStream("/fixtures/$path")) {
                "Missing fixture: $path"
            }
            .use { it.readBytes() }

    fun text(path: String): String = bytes(path).toString(Charsets.UTF_8)
}
