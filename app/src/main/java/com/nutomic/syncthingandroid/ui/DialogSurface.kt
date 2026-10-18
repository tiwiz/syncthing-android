package com.nutomic.syncthingandroid.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The container of a dialog-styled activity, drawn as a Material 3 dialog: [title] above [content]
 * on a surface with the dialog's colour and corners. The window itself is transparent and untitled
 * (`Theme.Syncthing.DialogActivity`).
 */
@Composable
fun DialogSurface(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 24.dp, top = 24.dp, end = 24.dp),
            )
            content()
        }
    }
}
