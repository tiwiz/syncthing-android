package com.nutomic.syncthingandroid.util

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Storage paths: the app's directories on external storage, mounted volumes, Syncthing's home, and
 * MIME types. Storage Access Framework URIs are in [SafUtils], opening files in [FileOpener].
 */
object FileUtils {

    private const val TAG = "FileUtils"
    internal const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    enum class ExternalStorageDirType {
        DATA,
        EXT_MEDIA,
        INT_MEDIA,
    }

    private fun getMountedStoragePaths(): List<String> =
        try {
            File("/proc/mounts").useLines { lines ->
                lines
                    .filter { it.contains("/storage/") || it.contains("/mnt/media_rw/") }
                    .map { it.splitLikeJava(" ")[1] }
                    .filter { mountPoint ->
                        (mountPoint.startsWith("/storage/") ||
                            mountPoint.startsWith("/mnt/media_rw/")) &&
                            !mountPoint.contains("emulated") &&
                            File(mountPoint).isDirectory
                    }
                    .toList()
            }
        } catch (e: IOException) {
            Log.w(TAG, "getMountedStoragePaths: Error reading /proc/mounts", e)
            emptyList()
        }

    /** Readable mounted storage volumes other than the emulated internal storage. */
    @JvmStatic
    fun getMountedStoragePathsAsFileArray(): Array<File> =
        getMountedStoragePaths().map(::File).filter { it.canRead() }.toTypedArray()

    @JvmStatic
    fun getExternalFilesDir(context: Context, type: String?): File? =
        getExternalFilesDir(context, ExternalStorageDirType.DATA, type)

    /**
     * The app's private folder on external storage if present, e.g.
     * "/storage/abcd-efgh/Android/data/[PACKAGE_NAME]/files" or
     * "/storage/abcd-efgh/Android/media/[PACKAGE_NAME]". For the media folders, [type]
     * Environment.DIRECTORY_PICTURES selects its "Pictures" subfolder.
     */
    @JvmStatic
    fun getExternalFilesDir(
        context: Context,
        extDirType: ExternalStorageDirType,
        type: String?,
    ): File? {
        val externalFilesDir = ArrayList<File?>()
        when (extDirType) {
            ExternalStorageDirType.DATA -> {
                externalFilesDir.addAll(ContextCompat.getExternalFilesDirs(context, null))
                if (externalFilesDir.size > 1) {
                    // There is a bug on Huawei devices running Android 7, which returns the wrong
                    // external path. That's why we use ContextCompat here instead of context.
                    // See:
                    // https://stackoverflow.com/questions/39895579/fileprovider-error-onhuawei-devices
                    externalFilesDir.remove(context.getExternalFilesDir(null))
                }
            }
            ExternalStorageDirType.INT_MEDIA ->
                externalFilesDir.add(
                    File(
                        Environment.getExternalStorageDirectory().toString() +
                            "/Android/media/" +
                            context.packageName
                    )
                )
            ExternalStorageDirType.EXT_MEDIA -> {
                @Suppress("DEPRECATION") // Still the only way to reach the SD card's media folder.
                externalFilesDir.addAll(context.externalMediaDirs)
                if (externalFilesDir.isNotEmpty()) {
                    externalFilesDir.removeAt(0)
                }
            }
        }
        // getExternalFilesDirs may return null for an ejected SDcard.
        externalFilesDir.remove(null)
        val first = externalFilesDir.firstOrNull()
        if (first == null) {
            Log.w(TAG, "Could not determine app's private files directory on external storage.")
            return null
        }
        if (extDirType != ExternalStorageDirType.DATA && type == Environment.DIRECTORY_PICTURES) {
            return File(first, Environment.DIRECTORY_PICTURES)
        }
        return first
    }

    /**
     * FileProvider does not support converting the absolute path from getExternalFilesDir() to a
     * "content://" Uri. As "file://" Uri has been blocked since Android 7+, we need to build the
     * Uri manually after discovering the first external storage. This is crucial to assist the user
     * finding a writeable folder to use Syncthing's two way sync feature.
     */
    @JvmStatic
    fun getExternalFilesDirUri(context: Context, extDirType: ExternalStorageDirType): Uri? {
        try {
            val externalFilesDir = getExternalFilesDir(context, extDirType, null)
            if (externalFilesDir == null) {
                Log.w(TAG, "Could not determine app's private files directory on external storage.")
                return null
            }
            val absPath = externalFilesDir.absolutePath
            val segments = absPath.splitLikeJava("/")
            if (segments.size < 2) {
                Log.w(TAG, "Could not extract volumeId from app's private files path '$absPath'")
                return null
            }
            // Extract the volumeId, e.g. "abcd-efgh"
            val volumeId = segments[2]
            val document =
                when (extDirType) {
                    // Our private ".../data/[PKG_NAME]/files" folder.
                    ExternalStorageDirType.DATA ->
                        "$volumeId%3AAndroid%2Fdata%2F${context.packageName}%2Ffiles"
                    // Our private ".../media/[PKG_NAME]" folder.
                    ExternalStorageDirType.EXT_MEDIA ->
                        "$volumeId%3AAndroid%2Fmedia%2F${context.packageName}"
                    ExternalStorageDirType.INT_MEDIA ->
                        "primary%3AAndroid%2Fmedia%2F${context.packageName}"
                }
            return "content://$EXTERNAL_STORAGE_AUTHORITY/document/$document".toUri()
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            // Any unexpected path layout means there is no such folder to offer.
            Log.w(TAG, "getExternalFilesDirUri exception", e)
            return null
        }
    }

    /**
     * FileProvider does not support converting absolute paths to a "content://" Uri. As "file://"
     * Uri has been blocked since Android 7+, we need to build the Uri manually.
     */
    @JvmStatic
    fun getInternalStorageRootUri(): Uri =
        "content://$EXTERNAL_STORAGE_AUTHORITY/document/primary%3A".toUri()

    /**
     * Reading the environment var avoids hard coding the absolute path of the "/Download" folder.
     */
    @JvmStatic
    fun getExternalStorageDownloadsDirectory(): String =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath

    @JvmStatic
    fun cutTrailingSlash(path: String): String =
        if (path.endsWith(File.separator)) path.substring(0, path.length - 1) else path

    /** Deletes a directory recursively; false if it did not exist or could not be deleted. */
    @JvmStatic
    @Throws(IOException::class)
    fun deleteDirectoryRecursively(dir: File?): Boolean {
        if (dir == null || !dir.exists()) return false
        if (dir.isFile) return dir.delete()
        dir.listFiles()?.forEach { deleteDirectoryRecursively(it) }
        return dir.delete()
    }

    /**
     * Expands the "~" path. Equals SyncthingRunnable env "HOME". Result: e.g.
     * /storage/emulated/0/syncthing
     */
    @JvmStatic
    fun getSyncthingTildeAbsolutePath(): String =
        getInternalStorageRootAbsolutePath() + "/syncthing"

    internal fun getInternalStorageRootAbsolutePath(): String =
        Environment.getExternalStorageDirectory().absolutePath

    /** The MIME type for a file extension, or "" if unknown. */
    @JvmStatic
    fun getMimeTypeFromFileExtension(fileExtension: String): String =
        MIME_TYPES[fileExtension.lowercase(Locale.ROOT)].orEmpty()
}
