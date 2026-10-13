package com.nutomic.syncthingandroid.util

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Storage Access Framework: tree and document URIs to absolute paths and back, and creating folders
 * and files through DocumentFile.
 */
object SafUtils {

    private const val TAG = "SafUtils"
    private const val DOWNLOADS_VOLUME_NAME = "downloads"
    private const val PRIMARY_VOLUME_NAME = "primary"
    private const val HOME_VOLUME_NAME = "home"

    /**
     * IN: content://com.android.externalstorage.documents/document/primary%3AAndroid%2Fmedia%2F...
     * OUT: content://com.android.externalstorage.documents/tree/primary%3AAndroid%2Fmedia%2F...
     */
    @JvmStatic
    fun convertFromDocumentUriToTreeUri(documentUri: Uri): Uri =
        DocumentsContract.buildTreeDocumentUri(
            documentUri.authority,
            DocumentsContract.getDocumentId(documentUri),
        )

    @JvmStatic
    fun directoryUriExists(context: Context, documentUri: Uri): Boolean =
        File(
                checkNotNull(
                    getAbsolutePathFromSAFUri(context, convertFromDocumentUriToTreeUri(documentUri))
                )
            )
            .exists()

    @JvmStatic
    fun getAbsolutePathFromSAFUri(context: Context, safResultUri: Uri?): String? {
        val treeUri =
            DocumentsContract.buildDocumentUriUsingTree(
                safResultUri,
                DocumentsContract.getTreeDocumentId(safResultUri),
            )
        return getAbsolutePathFromTreeUri(context, treeUri)
    }

    @JvmStatic
    fun getAbsolutePathFromTreeUri(context: Context, treeUri: Uri?): String? {
        if (treeUri == null) {
            Log.w(TAG, "getAbsolutePathFromTreeUri: called with treeUri == null")
            return null
        }
        // Determine volumeId, e.g. "home", "documents"
        val volumeId = getVolumeIdFromTreeUri(treeUri) ?: return null
        // Handle Uri referring to internal or external storage.
        val volumePath =
            FileUtils.cutTrailingSlash(getVolumePath(volumeId, context) ?: return File.separator)
        val documentPath = FileUtils.cutTrailingSlash(getDocumentPathFromTreeUri(treeUri))
        return when {
            documentPath.isEmpty() -> volumePath
            documentPath.startsWith(File.separator) -> volumePath + documentPath
            else -> volumePath + File.separator + documentPath
        }
    }

    @SuppressLint("ObsoleteSdkInt")
    /** The volume's mount point; null only when Android lists the volume without a path. */
    private fun getVolumePath(volumeId: String, context: Context): String? {
        if (HOME_VOLUME_NAME == volumeId) {
            Log.v(TAG, "getVolumePath: isHomeVolume")
            // Reading the environment var avoids hard coding the case of the "documents" folder.
            return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                .absolutePath
        }
        if (DOWNLOADS_VOLUME_NAME == volumeId) {
            Log.v(TAG, "getVolumePath: isDownloadsVolume")
            return FileUtils.getExternalStorageDownloadsDirectory()
        }
        findVolume(volumeId, context)?.let {
            return it.path
        }
        Log.w(TAG, "getVolumePath failed for volumeId='$volumeId'")
        if (volumeId == PRIMARY_VOLUME_NAME) {
            Log.d(TAG, "volumeId == primary")
            return FileUtils.getInternalStorageRootAbsolutePath()
        }
        return "/storage/$volumeId"
    }

    /** A mounted volume Android lists, with its path if it has one. */
    private class Volume(val path: String?)

    /** The mounted volume with [volumeId] ("primary" or its uuid), through reflection. */
    private fun findVolume(volumeId: String, context: Context): Volume? {
        try {
            val storageManager = context.getSystemService(StorageManager::class.java)
            val storageVolumeClazz = Class.forName("android.os.storage.StorageVolume")
            val getVolumeList = storageManager.javaClass.getMethod("getVolumeList")
            val getUuid = storageVolumeClazz.getMethod("getUuid")
            val getPath = storageVolumeClazz.getMethod("getPath")
            val isPrimary = storageVolumeClazz.getMethod("isPrimary")
            val result = getVolumeList.invoke(storageManager)
            for (i in 0 until java.lang.reflect.Array.getLength(result)) {
                val storageVolumeElement = java.lang.reflect.Array.get(result, i)
                val uuid = getUuid.invoke(storageVolumeElement) as String?
                val primary = isPrimary.invoke(storageVolumeElement) as Boolean
                val isPrimaryVolume = primary && PRIMARY_VOLUME_NAME == volumeId
                val isExternalVolume = uuid != null && uuid == volumeId
                Log.d(
                    TAG,
                    "Found volume with uuid='$uuid', volumeId='$volumeId', primary=$primary" +
                        ", isPrimaryVolume=$isPrimaryVolume, isExternalVolume=$isExternalVolume",
                )
                if (isPrimaryVolume || isExternalVolume) {
                    Log.v(TAG, "getVolumePath: isPrimaryVolume || isExternalVolume")
                    // Return path if the correct volume corresponding to volumeId was found.
                    return Volume(getPath.invoke(storageVolumeElement) as String?)
                }
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Hidden API: anything can go wrong, and the callers fall back to known paths.
            Log.w(TAG, "getVolumePath exception", e)
        }
        return null
    }

    private fun getVolumeIdFromTreeUri(treeUri: Uri): String? =
        DocumentsContract.getTreeDocumentId(treeUri).splitLikeJava(":").firstOrNull()

    private fun getDocumentPathFromTreeUri(treeUri: Uri): String {
        val split = DocumentsContract.getTreeDocumentId(treeUri).splitLikeJava(":")
        return if (split.size >= 2) split[1] else File.separator
    }

    /** The subfolder [folderName] of [parentFolder], created if needed; null if that failed. */
    @JvmStatic
    fun safCreateDirectory(parentFolder: DocumentFile?, folderName: String): DocumentFile? {
        if (parentFolder == null) {
            Log.w(TAG, "safCreateDirectory: parentFolder == null")
            return null
        }
        parentFolder
            .listFiles()
            .firstOrNull { it.isDirectory && it.name == folderName }
            ?.let {
                Log.v(TAG, "safCreateDirectory: Directory already exists '$folderName'")
                return it
            }
        val dfNewFolder = parentFolder.createDirectory(folderName)
        if (dfNewFolder == null) {
            Log.w(TAG, "safCreateDirectory: Failed to create directory '$folderName'")
            return null
        }
        Log.v(TAG, "safCreateDirectory: Created directory '$folderName'")
        return dfNewFolder
    }

    /**
     * Creates [fileNameAndExtension] with [content] (ISO-8859-1) in [parentFolder], unless it
     * exists. False if it could not be created.
     */
    @JvmStatic
    fun safCreateFile(
        context: Context,
        parentFolder: DocumentFile,
        fileNameAndExtension: String,
        content: String,
    ): Boolean {
        if (parentFolder.listFiles().any { it.isFile && it.name == fileNameAndExtension }) {
            Log.v(TAG, "safCreateFile: File already exists '$fileNameAndExtension'")
            return true
        }
        val fileExtension = MimeTypeMap.getFileExtensionFromUrl(fileNameAndExtension)
        val fileMimeType = FileUtils.getMimeTypeFromFileExtension(fileExtension)
        val dotIndex = fileNameAndExtension.lastIndexOf('.')
        val fileName =
            if (dotIndex > 0) fileNameAndExtension.substring(0, dotIndex) else fileNameAndExtension
        return try {
            val fileUri =
                DocumentsContract.createDocument(
                    context.contentResolver,
                    parentFolder.uri,
                    fileMimeType,
                    fileName,
                )
            if (fileUri == null) {
                Log.e(TAG, "safCreateFile: Failed to create file '$fileNameAndExtension' #1")
                return false
            }
            checkNotNull(context.contentResolver.openOutputStream(fileUri)).use { outputStream ->
                if (content.isNotEmpty()) {
                    outputStream.write(content.toByteArray(Charsets.ISO_8859_1))
                }
                outputStream.flush()
            }
            Log.v(TAG, "safCreateFile: Created file '$fileNameAndExtension', type '$fileMimeType'")
            true
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // The document provider decides what can fail; any failure means no file.
            Log.e(TAG, "safCreateFile: Failed to create file '$fileNameAndExtension' #2", e)
            false
        }
    }

    /**
     * Converts a raw file path into a Storage Access Framework Document ID, including support for
     * removable SD cards and USB OTG drives; null for other paths.
     */
    @SuppressLint("SdCardPath")
    internal fun getDocumentIdFromPath(path: String?): String? {
        if (path.isNullOrEmpty()) {
            return null
        }
        // Clean up trailing slashes for consistent parsing
        val fullPath = if (path.endsWith("/") && path.length > 1) path.dropLast(1) else path
        val internalStoragePath = "/storage/emulated/0"
        val sdcardPath = "/sdcard"
        val storagePrefix = "/storage/"
        val primary = "primary:"
        return when {
            // 1. Internal storage
            fullPath == internalStoragePath || fullPath == sdcardPath -> primary
            fullPath.startsWith("$internalStoragePath/") ->
                primary + fullPath.substring(internalStoragePath.length + 1)
            fullPath.startsWith("$sdcardPath/") ->
                primary + fullPath.substring(sdcardPath.length + 1)
            // 2. Removable SD cards and USB drives: /storage/1A2B-3C4D/books/sync
            fullPath.startsWith(storagePrefix) -> {
                val withoutStorage = fullPath.substring(storagePrefix.length)
                val firstSlashIndex = withoutStorage.indexOf('/')
                if (firstSlashIndex == -1) {
                    // The root of the SD card: /storage/1A2B-3C4D
                    "$withoutStorage:"
                } else {
                    withoutStorage.substring(0, firstSlashIndex) +
                        ":" +
                        withoutStorage.substring(firstSlashIndex + 1)
                }
            }
            // Not a known external storage path.
            else -> null
        }
    }
}
