package com.nutomic.syncthingandroid.activities

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nutomic.syncthingandroid.R
import java.util.concurrent.TimeUnit

internal const val KEY_TYPE = "type"

/** The versioning types, in the order of the "versioning_types" string array. */
private val VERSIONING_TYPES = listOf("none", "trashcan", "simple", "staggered", "external")

private const val MAX_KEEP_VERSIONS = 100_000
private const val MAX_DAYS = 100

/** Fills in the parameters the current type needs and does not have yet. */
internal fun fillVersioningDefaults(params: MutableMap<String, String>) {
    when (params[KEY_TYPE]) {
        "trashcan" -> params.getOrPut("cleanoutDays") { "0" }
        "simple" -> {
            params.getOrPut("keep") { "5" }
            params.getOrPut("cleanoutDays") { "0" }
        }
        "staggered" ->
            if ("maxAge" !in params) {
                params["maxAge"] = "0"
                params["versionsPath"] = ""
            }
        "external" -> params.getOrPut("command") { "" }
    }
}

/** The versioning type choice, the parameters of the chosen type, and a "Finish" button. */
@Composable
internal fun VersioningScreen(
    params: Map<String, String>,
    onTypeChange: (String) -> Unit,
    onParamChange: (String, String) -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val type = params[KEY_TYPE]
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TypeChoice(type = type, onTypeChange = onTypeChange)
        Column(
            modifier = Modifier.padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (type) {
                "none" -> Description(R.string.no_versioning_description)
                "trashcan" -> {
                    Description(R.string.trashcan_versioning_description)
                    CleanoutDays(params, onParamChange)
                }
                "simple" -> {
                    Description(R.string.simple_file_versioning_description)
                    NumberField(
                        key = "keep",
                        value = params["keep"].orEmpty(),
                        range = 1..MAX_KEEP_VERSIONS,
                        label = R.string.keep_versions,
                        description = R.string.keep_versions_description,
                        onValueChange = { onParamChange("keep", it.toString()) },
                    )
                    CleanoutDays(params, onParamChange)
                }
                "staggered" -> StaggeredParams(params, onParamChange)
                "external" -> {
                    Description(R.string.external_versioning_description)
                    OutlinedTextField(
                        value = params["command"].orEmpty(),
                        onValueChange = { onParamChange("command", it) },
                        label = { Text(stringResource(R.string.command)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("command"),
                    )
                }
            }
        }
        TextButton(
            onClick = onFinish,
            modifier = Modifier.align(Alignment.End).padding(horizontal = 16.dp),
        ) {
            Text(stringResource(R.string.finish))
        }
    }
}

@Composable
private fun TypeChoice(type: String?, onTypeChange: (String) -> Unit) {
    val names = stringArrayResource(R.array.versioning_types)
    Column(modifier = Modifier.selectableGroup()) {
        for ((value, label) in VERSIONING_TYPES.zip(names)) {
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(
                            selected = value == type,
                            role = Role.RadioButton,
                            onClick = { if (value != type) onTypeChange(value) },
                        )
                        .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = value == type, onClick = null)
                Text(
                    text = label,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Composable
private fun Description(text: Int) {
    Text(
        text = stringResource(text),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun CleanoutDays(params: Map<String, String>, onParamChange: (String, String) -> Unit) {
    NumberField(
        key = "cleanoutDays",
        value = params["cleanoutDays"].orEmpty(),
        range = 0..MAX_DAYS,
        label = R.string.clean_out_after,
        description = R.string.cleanout_after_description,
        onValueChange = { onParamChange("cleanoutDays", it.toString()) },
    )
}

@Composable
private fun StaggeredParams(params: Map<String, String>, onParamChange: (String, String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Description(R.string.staggered_versioning_description)
        // Shown in days, stored in seconds as Syncthing wants it.
        val maxAgeDays = params["maxAge"]?.toLongOrNull()?.let(TimeUnit.SECONDS::toDays) ?: 0
        NumberField(
            key = "maxAge",
            value = maxAgeDays.toString(),
            range = 0..MAX_DAYS,
            label = R.string.maximum_age,
            description = R.string.maximum_age_description,
            onValueChange = {
                onParamChange("maxAge", TimeUnit.DAYS.toSeconds(it.toLong()).toString())
            },
        )
        val context = LocalContext.current
        val versionsPath = params["versionsPath"].orEmpty()
        val picker =
            rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                val directory = it.data?.getStringExtra(FolderPickerActivity.EXTRA_RESULT_DIRECTORY)
                if (it.resultCode == Activity.RESULT_OK && directory != null) {
                    onParamChange("versionsPath", directory)
                }
            }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.versions_path),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
            )
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable {
                            picker.launch(
                                FolderPickerActivity.createIntent(context, versionsPath, null)
                            )
                        }
                        .testTag("versionsPath"),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = versionsPath.ifEmpty { stringResource(R.string.directory) },
                    color =
                        if (versionsPath.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Description(R.string.versions_path_description)
        }
    }
}

/**
 * A number in [range]. Digits only; what the user types is kept as typed, the parameter gets the
 * value clamped to [range], and an empty field leaves it unchanged.
 */
@Composable
private fun NumberField(
    key: String,
    value: String,
    range: IntRange,
    label: Int,
    description: Int,
    onValueChange: (Int) -> Unit,
) {
    var text by rememberSaveable(key) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            val digits = input.filter(Char::isDigit).take(range.last.toString().length)
            text = digits
            digits.toIntOrNull()?.let { onValueChange(it.coerceIn(range)) }
        },
        label = { Text(stringResource(label)) },
        supportingText = { Text(stringResource(description)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag(key),
    )
}
