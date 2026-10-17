package com.nutomic.syncthingandroid.activities

import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.nutomic.syncthingandroid.util.FileUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where the folder picker is. The roots come from the intent; the folder shown survives a
 * recreation and process death.
 */
@HiltViewModel
internal class FolderPickerViewModel
@Inject
constructor(@ApplicationContext context: Context, private val savedState: SavedStateHandle) :
    ViewModel() {

    private val state: MutableStateFlow<FolderPickerState>
    val uiState: StateFlow<FolderPickerState>

    init {
        val roots = findRoots(context, savedState[FolderPickerActivity.EXTRA_ROOT_DIRECTORY])
        val start = FolderPickerState(roots, null)
        val initial: String? = savedState[FolderPickerActivity.EXTRA_INITIAL_DIRECTORY]
        state =
            MutableStateFlow(
                when {
                    // An empty path is the list of roots.
                    savedState.contains(LOCATION) ->
                        start.copy(
                            location =
                                savedState.get<String>(LOCATION)?.ifEmpty { null }?.let(::File)
                        )
                    initial != null -> start.copy(location = File(initial))
                    else -> start.atRoot()
                }
            )
        uiState = state.asStateFlow()
    }

    fun open(folder: File) {
        show(state.value.copy(location = folder))
    }

    fun up() {
        if (!state.value.canGoUpToSubDir && !state.value.canGoUpToRootDir) {
            Log.e(TAG, "goUpToParentDir: Cannot go up.")
            return
        }
        show(state.value.up())
    }

    /** Creates a folder named [name] in the one shown and enters it; false if that failed. */
    fun createFolder(name: String): Boolean {
        val newFolder = File(state.value.location, name)
        if (!newFolder.mkdir()) {
            return false
        }
        open(newFolder)
        return true
    }

    private fun show(newState: FolderPickerState) {
        savedState[LOCATION] = newState.location?.path.orEmpty()
        state.value = newState
    }

    private companion object {
        const val TAG = "FolderPickerActivity"
        const val LOCATION = "location"
    }
}

/**
 * [rootDir], or all storage devices and folders the APIs know about; only existing directories,
 * sorted.
 */
private fun findRoots(context: Context, rootDir: String?): List<File> {
    val roots =
        if (!rootDir.isNullOrEmpty()) {
            listOf(File(rootDir))
        } else {
            // getExternalFilesDirs may return null for an ejected SD card.
            context.getExternalFilesDirs(null).filterNotNull().filter {
                it != context.getExternalFilesDir(null)
            } +
                @Suppress("DEPRECATION") // The shared storage roots the picker offers.
                listOf(
                    Environment.getExternalStorageDirectory(),
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                ) +
                // Paths where we might have read-only access.
                FileUtils.getMountedStoragePathsAsFileArray() +
                File("/")
        }
    return roots.filter { it.exists() && it.isDirectory }.toSortedSet().toList()
}
