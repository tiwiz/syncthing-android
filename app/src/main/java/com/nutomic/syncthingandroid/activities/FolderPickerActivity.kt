package com.nutomic.syncthingandroid.activities

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.text.TextUtils
import android.util.Log
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.theme.ApplicationTheme
import com.nutomic.syncthingandroid.util.FileUtils
import com.nutomic.syncthingandroid.util.formatPath
import java.io.File

/** Picks a directory in the local file system, below one root or among all storage roots. */
class FolderPickerActivity : SyncthingActivity() {

    private var state by mutableStateOf(FolderPickerState(emptyList(), null))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val roots = findRoots()
        val initial = intent.getStringExtra(EXTRA_INITIAL_DIRECTORY)
        state =
            FolderPickerState(roots, null).let {
                if (initial != null) it.copy(location = File(initial)) else it.atRoot()
            }
        setContent {
            ApplicationTheme {
                FolderPickerScreen(
                    state = state,
                    onOpen = { state = state.copy(location = it) },
                    onUp = ::goUpToParentDir,
                    onSelect = ::selectFolder,
                    onCreateFolder = ::createFolder,
                    onNavigateBack = ::finish,
                )
            }
        }
        onBackPressedDispatcher.addCallback(this) {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }

    /**
     * The root given by [createIntent], or all storage devices and folders the APIs know about;
     * only existing directories, sorted.
     */
    private fun findRoots(): List<File> {
        val rootDir = intent.getStringExtra(EXTRA_ROOT_DIRECTORY)
        val roots =
            if (!rootDir.isNullOrEmpty()) {
                listOf(File(rootDir))
            } else {
                // getExternalFilesDirs may return null for an ejected SD card.
                getExternalFilesDirs(null).filterNotNull().filter {
                    it != getExternalFilesDir(null)
                } +
                    @Suppress("DEPRECATION") // The shared storage roots the picker offers.
                    listOf(
                        Environment.getExternalStorageDirectory(),
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                        Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_PICTURES
                        ),
                        Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS
                        ),
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                        Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOCUMENTS
                        ),
                    ) +
                    // Paths where we might have read-only access.
                    FileUtils.getMountedStoragePathsAsFileArray() +
                    File("/")
            }
        return roots.filter { it.exists() && it.isDirectory }.toSortedSet().toList()
    }

    fun canGoUpToSubDir(): Boolean = state.canGoUpToSubDir

    fun canGoUpToRootDir(): Boolean = state.canGoUpToRootDir

    fun goUpToParentDir() {
        if (!canGoUpToSubDir() && !canGoUpToRootDir()) {
            Log.e(TAG, "goUpToParentDir: Cannot go up.")
            return
        }
        state = state.up()
    }

    private fun selectFolder() {
        val location = state.location ?: return
        setResult(
            Activity.RESULT_OK,
            Intent().putExtra(EXTRA_RESULT_DIRECTORY, formatPath(location.absolutePath)),
        )
        finish()
    }

    /** Creates a new folder with the given name and enters it. */
    private fun createFolder(name: String) {
        val newFolder = File(state.location, name)
        if (newFolder.mkdir()) {
            state = state.copy(location = newFolder)
        } else {
            Toast.makeText(this, R.string.create_folder_failed, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "FolderPickerActivity"

        private const val EXTRA_INITIAL_DIRECTORY =
            ".activities.FolderPickerActivity.INITIAL_DIRECTORY"

        /** With it, the picker has this one root and the user stays within it. */
        private const val EXTRA_ROOT_DIRECTORY = ".activities.FolderPickerActivity.ROOT_DIRECTORY"

        const val EXTRA_RESULT_DIRECTORY = ".activities.FolderPickerActivity.RESULT_DIRECTORY"

        const val DIRECTORY_REQUEST_CODE = 234

        @JvmStatic
        fun createIntent(
            context: Context,
            initialDirectory: String?,
            rootDirectory: String?,
        ): Intent {
            val intent = Intent(context, FolderPickerActivity::class.java)
            if (!TextUtils.isEmpty(initialDirectory)) {
                intent.putExtra(EXTRA_INITIAL_DIRECTORY, initialDirectory)
            }
            if (!TextUtils.isEmpty(rootDirectory)) {
                intent.putExtra(EXTRA_ROOT_DIRECTORY, rootDirectory)
            }
            return intent
        }
    }
}
