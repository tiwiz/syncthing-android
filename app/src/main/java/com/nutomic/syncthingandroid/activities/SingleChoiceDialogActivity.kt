package com.nutomic.syncthingandroid.activities

import android.content.Intent
import android.os.Bundle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.annotation.ArrayRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.theme.ApplicationTheme
import com.nutomic.syncthingandroid.ui.groupedItemShape

/**
 * A dialog-styled screen that picks one of a few values and hands it back as its result, on
 * "Finish" and on back alike. The window title comes from the activity label.
 */
abstract class SingleChoiceDialogActivity : ThemedAppCompatActivity() {

    /** The values to choose from, in the order of [labels]. */
    protected abstract val values: List<String>

    /** The string array naming the [values]; may be shorter, leaving the last values out. */
    @get:ArrayRes protected abstract val labels: Int

    /** The intent extra with the current value. */
    protected abstract val extraValue: String

    /** The result extra with the chosen value. */
    protected abstract val extraResult: String

    private var selected by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selected =
            savedInstanceState?.getString(STATE_SELECTED) ?: intent.getStringExtra(extraValue)
        setContent {
            ApplicationTheme {
                val names = stringArrayResource(labels)
                SingleChoiceList(
                    options = values.zip(names),
                    selected = selected,
                    onSelect = { selected = it },
                    onFinish = ::finishWithResult,
                )
            }
        }
        onBackPressedDispatcher.addCallback(this) { finishWithResult() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SELECTED, selected)
    }

    private fun finishWithResult() {
        setResult(RESULT_OK, Intent().putExtra(extraResult, selected))
        finish()
    }

    private companion object {
        const val STATE_SELECTED = "selected"
    }
}

/** Radio buttons for [options] (value to label) as a grouped list, and a "Finish" button. */
@Composable
internal fun SingleChoiceList(
    options: List<Pair<String, String>>,
    selected: String?,
    onSelect: (String) -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Column(
            modifier = Modifier.selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(GROUP_GAP.dp),
        ) {
            options.forEachIndexed { index, (value, label) ->
                ChoiceOption(
                    label = label,
                    selected = value == selected,
                    onSelect = { onSelect(value) },
                    shape = groupedItemShape(index, options.size),
                )
            }
        }
        TextButton(
            onClick = onFinish,
            modifier = Modifier.align(Alignment.End).padding(top = 8.dp),
        ) {
            Text(stringResource(R.string.finish))
        }
    }
}

/**
 * One choice of a grouped list: a radio button and its label on a group item; [shape] places it in
 * the group.
 */
@Composable
internal fun ChoiceOption(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
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
                .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        // The dialog window draws the background; the text follows the theme.
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
