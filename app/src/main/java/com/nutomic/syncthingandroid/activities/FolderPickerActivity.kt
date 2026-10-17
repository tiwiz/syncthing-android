package com.nutomic.syncthingandroid.activities

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.theme.ApplicationTheme
import com.nutomic.syncthingandroid.util.formatPath
import dagger.hilt.android.AndroidEntryPoint

/** Picks a directory in the local file system, below one root or among all storage roots. */
@AndroidEntryPoint
class FolderPickerActivity : ThemedAppCompatActivity() {

    private val viewModel: FolderPickerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            ApplicationTheme {
                FolderPickerScreen(
                    state = state,
                    onOpen = viewModel::open,
                    onUp = viewModel::up,
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

    fun canGoUpToSubDir(): Boolean = viewModel.uiState.value.canGoUpToSubDir

    fun canGoUpToRootDir(): Boolean = viewModel.uiState.value.canGoUpToRootDir

    private fun selectFolder() {
        val location = viewModel.uiState.value.location ?: return
        setResult(
            Activity.RESULT_OK,
            Intent().putExtra(EXTRA_RESULT_DIRECTORY, formatPath(location.absolutePath)),
        )
        finish()
    }

    /** Creates a new folder with the given name and enters it. */
    private fun createFolder(name: String) {
        if (!viewModel.createFolder(name)) {
            Toast.makeText(this, R.string.create_folder_failed, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        internal const val EXTRA_INITIAL_DIRECTORY =
            ".activities.FolderPickerActivity.INITIAL_DIRECTORY"

        /** With it, the picker has this one root and the user stays within it. */
        internal const val EXTRA_ROOT_DIRECTORY = ".activities.FolderPickerActivity.ROOT_DIRECTORY"

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
