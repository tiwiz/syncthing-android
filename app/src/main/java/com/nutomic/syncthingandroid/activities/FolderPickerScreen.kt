package com.nutomic.syncthingandroid.activities

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.ui.groupedItemShape
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
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(GROUP_GAP.dp),
                ) {
                    itemsIndexed(entries, key = { _, file -> file.absolutePath }) { index, file ->
                        PickerRow(
                            file = file,
                            showFullPath = state.location == null,
                            onOpen = onOpen,
                            shape = groupedItemShape(index, entries.size),
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

/** One file or folder of the picker, as a group item; [shape] places it in the group. */
@Composable
private fun PickerRow(
    file: File,
    showFullPath: Boolean,
    onOpen: (File) -> Unit,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .clickable(enabled = file.isDirectory) { onOpen(file) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector =
                if (file.isDirectory) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = if (showFullPath) file.absolutePath else file.name,
            style = MaterialTheme.typography.bodyLarge,
            fontStyle = if (file.isFile) FontStyle.Italic else FontStyle.Normal,
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
