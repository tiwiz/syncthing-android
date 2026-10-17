package com.nutomic.syncthingandroid.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.ui.SectionHeader
import com.nutomic.syncthingandroid.ui.groupedItemShape

private const val GROUP_GAP_DP = 2

/**
 * A group of settings items on the same surface, with rounded outer corners and tight inner ones.
 */
@Composable
internal fun SettingsGroup(
    items: List<@Composable (Shape) -> Unit>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(GROUP_GAP_DP.dp),
    ) {
        items.forEachIndexed { index, item -> item(groupedItemShape(index, items.size)) }
    }
}

/** The title above a group of settings items. */
@Composable
@NonRestartableComposable
internal fun SettingsSectionHeader(text: String, modifier: Modifier = Modifier) {
    SectionHeader(text = text, modifier = modifier)
}

/** A section header followed by a [SettingsGroup] inside a [LazyColumn]. */
internal fun LazyListScope.groupedSection(
    titleRes: Int,
    items: List<@Composable (Shape) -> Unit>,
) {
    item {
        SettingsSectionHeader(
            text = stringResource(titleRes),
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
    item {
        SettingsGroup(
            items = items,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

/** The surface for one item of a [SettingsGroup]. */
@Composable
private fun SettingsItemSurface(
    shape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.clip(shape),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        content = content,
    )
}

/** A clickable settings row with a title and an optional summary. */
@Composable
internal fun SettingsItem(
    title: String,
    shape: Shape,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    trailing: @Composable (() -> Unit)? = null,
) {
    SettingsItemSurface(shape, modifier) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = summary?.let { { Text(it) } },
            trailingContent = trailing,
            colors =
                ListItemDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
            modifier =
                Modifier.fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable(enabled = enabled, onClick = onClick),
        )
    }
}

/** A switch settings row. */
@Composable
internal fun SettingsSwitch(
    title: String,
    checked: Boolean,
    shape: Shape,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
) {
    SettingsItemSurface(shape, modifier) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = summary?.let { { Text(it) } },
            trailingContent = {
                Switch(checked = checked, onCheckedChange = null, enabled = enabled)
            },
            colors =
                ListItemDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
            modifier =
                Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(
                    value = checked,
                    role = Role.Switch,
                    enabled = enabled,
                ) {
                    onCheckedChange(!checked)
                },
        )
    }
}

/** A settings row that opens a single-choice dialog. */
@Composable
internal fun SettingsListItem(
    title: String,
    value: String,
    values: List<String>,
    valueToText: (String) -> String,
    shape: Shape,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    SettingsItem(
        title = title,
        summary = valueToText(value),
        shape = shape,
        enabled = enabled,
        onClick = { showDialog = true },
        modifier = modifier,
    )
    if (showDialog) {
        SingleChoiceDialog(
            title = title,
            value = value,
            values = values,
            valueToText = valueToText,
            onValueChange = onValueChange,
            onDismiss = { showDialog = false },
        )
    }
}

/** A settings row that opens a multi-choice dialog. */
@Composable
internal fun SettingsMultiSelectListItem(
    title: String,
    value: Set<String>,
    values: List<String>,
    valueToText: (String) -> String,
    emptyText: String,
    shape: Shape,
    onValueChange: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val summary =
        remember(value) {
            if (value.isEmpty()) emptyText else value.joinToString { valueToText(it) }
        }
    SettingsItem(
        title = title,
        summary = summary,
        shape = shape,
        enabled = enabled,
        onClick = { showDialog = true },
        modifier = modifier,
    )
    if (showDialog) {
        MultiChoiceDialog(
            title = title,
            value = value,
            values = values,
            valueToText = valueToText,
            onValueChange = onValueChange,
            onDismiss = { showDialog = false },
        )
    }
}

/** A settings row that opens a text-field dialog. */
@Composable
internal fun SettingsTextFieldItem(
    title: String,
    value: String,
    shape: Shape,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    textToValue: (String) -> String? = { it },
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    enabled: Boolean = true,
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    SettingsItem(
        title = title,
        summary = summary ?: value,
        shape = shape,
        enabled = enabled,
        onClick = { showDialog = true },
        modifier = modifier,
    )
    if (showDialog) {
        TextFieldDialog(
            title = title,
            value = value,
            onValueChange = onValueChange,
            textToValue = textToValue,
            keyboardOptions = keyboardOptions,
            onDismiss = { showDialog = false },
        )
    }
}

/** A settings row that opens a password text-field dialog with a reveal toggle. */
@Composable
internal fun SettingsPasswordItem(
    title: String,
    value: String,
    shape: Shape,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    SettingsItem(
        title = title,
        summary = summary ?: value,
        shape = shape,
        enabled = enabled,
        onClick = { showDialog = true },
        modifier = modifier,
    )
    if (showDialog) {
        PasswordDialog(
            title = title,
            value = value,
            onValueChange = onValueChange,
            onDismiss = { showDialog = false },
        )
    }
}

@Composable
private fun SingleChoiceDialog(
    title: String,
    value: String,
    values: List<String>,
    valueToText: (String) -> String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn {
                items(values) { item ->
                    val selected = item == value
                    Row(
                        modifier =
                            Modifier.fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(selected = selected, role = Role.RadioButton) {
                                    onValueChange(item)
                                    onDismiss()
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Text(
                            text = valueToText(item),
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun MultiChoiceDialog(
    title: String,
    value: Set<String>,
    values: List<String>,
    valueToText: (String) -> String,
    onValueChange: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var dialogValue by rememberSaveable { mutableStateOf(value) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn {
                items(values) { item ->
                    val checked = item in dialogValue
                    Row(
                        modifier =
                            Modifier.fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .toggleable(value = checked, role = Role.Checkbox) {
                                    dialogValue = if (it) dialogValue + item else dialogValue - item
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Text(
                            text = valueToText(item),
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onValueChange(dialogValue)
                    onDismiss()
                }
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun TextFieldDialog(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    textToValue: (String) -> String?,
    keyboardOptions: KeyboardOptions,
    onDismiss: () -> Unit,
) {
    var dialogValue by rememberSaveable { mutableStateOf(value) }
    val onConfirm = {
        val newValue = textToValue(dialogValue)
        if (newValue != null) {
            onValueChange(newValue)
            onDismiss()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = dialogValue,
                onValueChange = { dialogValue = it },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = keyboardOptions,
                keyboardActions = KeyboardActions { onConfirm() },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun PasswordDialog(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var dialogValue by rememberSaveable { mutableStateOf(value) }
    var visible by rememberSaveable { mutableStateOf(false) }
    val onConfirm = {
        onValueChange(dialogValue)
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = dialogValue,
                onValueChange = { dialogValue = it },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation =
                    if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            imageVector =
                                if (visible) Icons.Filled.VisibilityOff
                                else Icons.Filled.Visibility,
                            contentDescription =
                                stringResource(R.string.toggle_password_visibility),
                        )
                    }
                },
                keyboardActions = KeyboardActions { onConfirm() },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
