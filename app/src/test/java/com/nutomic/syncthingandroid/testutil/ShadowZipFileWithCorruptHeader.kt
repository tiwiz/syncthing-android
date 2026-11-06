package com.nutomic.syncthingandroid.testutil

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.FileHeader
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * A zip4j [ZipFile] that fails with an unchecked exception while reading its headers. zip4j wraps
 * every failure seen with real corrupt or unreadable files in a `ZipException`, so this stands in
 * for the ones it does not (#149). Needs `instrumentedPackages = ["net.lingala.zip4j"]`.
 */
@Implements(ZipFile::class)
class ShadowZipFileWithCorruptHeader {
    @Implementation
    fun getFileHeader(@Suppress("UNUSED_PARAMETER") fileName: String): FileHeader = error(MESSAGE)

    companion object {
        const val MESSAGE = "corrupt central directory"
    }
}
