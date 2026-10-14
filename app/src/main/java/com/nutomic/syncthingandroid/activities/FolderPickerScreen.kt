package com.nutomic.syncthingandroid.activities

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.nutomic.syncthingandroid.R
import java.io.File

/** The folder picker: the current path, its folders and files, and the actions on a folder. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FolderPickerScreen(
    state: FolderPickerState,
    onOpen: (File) -> Unit,
    onUp: () -> Unit,
    onSelect: () -> Unit,
    onCreateFolder: (String) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var creating by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.folder_picker_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    if (state.location != null) {
                        IconButton(onClick = onSelect) {
                            Icon(Icons.Filled.Done, stringResource(R.string.select_folder))
                        }
                        IconButton(onClick = onUp) {
                            Icon(Icons.Filled.ArrowUpward, stringResource(R.string.folder_go_up))
                        }
                        IconButton(onClick = { creating = true }) {
                            Icon(
                                Icons.Filled.CreateNewFolder,
                                stringResource(R.string.create_fs_folder),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Text(
                text =
                    state.location?.let { stringResource(R.string.current_path, it.absolutePath) }
                        ?: stringResource(R.string.advanced_storage_path_overview),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            val entries = state.entries
            if (entries.isEmpty()) {
                Text(
                    text = stringResource(R.string.directory_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(16.dp).align(Alignment.CenterHorizontally),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(entries, key = { it.absolutePath }) { file ->
                        Text(
                            text = if (state.location == null) file.absolutePath else file.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontStyle = if (file.isFile) FontStyle.Italic else FontStyle.Normal,
                            modifier =
                                Modifier.fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clickable(enabled = file.isDirectory) { onOpen(file) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
    if (creating) {
        CreateFolderDialog(
            onDismiss = { creating = false },
            onCreate = {
                creating = false
                onCreateFolder(it)
            },
        )
    }
}

@Composable
private fun CreateFolderDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.create_folder)) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true)
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name) }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
