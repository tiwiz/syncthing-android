package com.nutomic.syncthingandroid.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.theme.ApplicationTheme
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import kotlinx.coroutines.launch

/** Saves files shared from other apps into a Syncthing folder. */
@AndroidEntryPoint
class ShareActivity : ThemedAppCompatActivity() {

    private val viewModel: ShareViewModel by viewModels()

    private val pickDirectory =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val directory = result.data?.getStringExtra(FolderPickerActivity.EXTRA_RESULT_DIRECTORY)
            if (result.resultCode == RESULT_OK && directory != null) {
                viewModel.onDirectoryPicked(directory)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = sharedUris()
        if (shared.isEmpty()) {
            Toast.makeText(this, R.string.nothing_share, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        viewModel.start(shared)

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            ApplicationTheme {
                ShareScreen(
                    names = state.names,
                    multipleFiles = state.multipleFiles,
                    folders = state.folders,
                    selectedFolder = state.selectedFolder,
                    subDirectory = state.subDirectory,
                    copying = state.copying,
                    onNamesChange = viewModel::onNamesChange,
                    onFolderSelect = viewModel::selectFolder,
                    onBrowse = ::browse,
                    onSave = viewModel::save,
                    onCancel = ::finish,
                )
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) { viewModel.finishes.collect { finish() } }
        }
    }

    private fun sharedUris(): List<Uri> =
        when (intent.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(
                    IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                )
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(
                        intent,
                        Intent.EXTRA_STREAM,
                        Uri::class.java,
                    )
                    .orEmpty()
            else -> emptyList()
        }

    private fun browse() {
        val folder = viewModel.uiState.value.selectedFolder ?: return
        val initialDirectory = File(folder.path, viewModel.uiState.value.subDirectory)
        pickDirectory.launch(
            FolderPickerActivity.createIntent(
                applicationContext,
                initialDirectory.absolutePath,
                folder.path,
            )
        )
    }

    override fun onPause() {
        super.onPause()
        viewModel.rememberSelection()
    }

    companion object {
        internal const val PREF_PREVIOUSLY_SELECTED_SYNCTHING_FOLDER =
            "previously_selected_syncthing_folder"

        const val PREF_FOLDER_SAVED_SUBDIRECTORY = "saved_sub_directory_"
    }
}
