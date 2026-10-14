package com.nutomic.syncthingandroid.service

import android.util.Log
import com.nutomic.syncthingandroid.util.CertificateValidator
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

// File handling for SyncthingService's HTTPS certificate changes: backups, atomic writes, rollback.

private const val TAG = "SyncthingService"

/**
 * Whether the certificate currently on disk is the one identified by [expected]. A missing or
 * unreadable file counts as "not it", which routes to the rollback path.
 */
internal fun certFileHasFingerprint(certFile: File, expected: String): Boolean =
    try {
        expected == CertificateValidator.leafFingerprint(certFile.readBytes())
    } catch (e: IOException) {
        Log.w(TAG, "certFileHasFingerprint: Failed to read ${certFile.name}", e)
        false
    }

/** Moves [file] to "<name>.bak" and returns the backup, or null without a file to back up. */
internal fun backupFile(file: File): File? {
    if (!file.exists()) {
        return null
    }
    val bak = File(file.parentFile, file.name + ".bak")
    deleteQuietly(bak)
    if (file.renameTo(bak)) {
        return bak
    }
    Log.w(TAG, "backupFile: Failed to back up ${file.name}")
    return null
}

/** Puts the backup [bak] back in place of [target]. */
internal fun restoreFile(bak: File?, target: File) {
    if (bak == null || !bak.exists()) {
        return
    }
    deleteQuietly(target)
    if (!bak.renameTo(target)) {
        Log.w(TAG, "restoreFile: Failed to restore ${target.name}")
    }
}

internal fun deleteQuietly(file: File?) {
    if (file != null && file.exists() && !file.delete()) {
        Log.w(TAG, "deleteQuietly: Failed to delete ${file.name}")
    }
}

/** Writes [data] to a temporary file next to [target], then renames it over [target]. */
@Throws(IOException::class)
internal fun writeBytesAtomic(target: File, data: ByteArray) {
    val tmp = File(target.parentFile, target.name + ".tmp")
    FileOutputStream(tmp).use { fos ->
        fos.write(data)
        fos.flush()
        fos.fd.sync()
    }
    if (!tmp.renameTo(target)) {
        deleteQuietly(tmp)
        throw IOException("Failed to rename ${tmp.name} to ${target.name}")
    }
}

/** Mirrors Syncthing core, which writes the HTTPS key with 0600 permissions. */
internal fun restrictToOwner(file: File) {
    file.setReadable(false, false)
    file.setReadable(true, true)
    file.setWritable(false, false)
    file.setWritable(true, true)
    file.setExecutable(false, false)
}
