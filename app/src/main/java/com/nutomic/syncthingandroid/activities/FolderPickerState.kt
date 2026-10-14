package com.nutomic.syncthingandroid.activities

import androidx.compose.runtime.Immutable
import java.io.File

/**
 * Where the folder picker is: the list of [roots] when [location] is null, else a folder. Moving up
 * stops at the roots, and skips their list when there is only one.
 */
@Immutable
internal data class FolderPickerState(val roots: List<File>, val location: File?) {

    val canGoUpToSubDir: Boolean
        get() = location != null && location !in roots

    val canGoUpToRootDir: Boolean
        get() = location in roots && roots.size > 1

    /** The folder's contents, folders first, by name ignoring case; or the roots. */
    val entries: List<File>
        get() =
            location?.let { folder ->
                folder
                    .listFiles()
                    .orEmpty()
                    // In case we don't have read access to the folder, just display nothing.
                    .sortedWith(
                        compareBy<File> { !it.isDirectory }
                            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                    )
            } ?: roots

    fun up(): FolderPickerState =
        when {
            canGoUpToSubDir -> copy(location = checkNotNull(location).parentFile)
            canGoUpToRootDir -> atRoot()
            else -> this
        }

    /** The roots, or the only root's contents. */
    fun atRoot(): FolderPickerState = copy(location = roots.singleOrNull())
}
