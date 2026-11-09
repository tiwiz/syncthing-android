package com.nutomic.syncthingandroid.ui

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.window.core.layout.WindowSizeClass
import androidx.xr.compose.platform.LocalSpatialCapabilities
import androidx.xr.compose.platform.LocalSpatialConfiguration
import androidx.xr.compose.platform.SpaceRequestResult
import androidx.xr.compose.platform.requestFullSpace
import androidx.xr.compose.platform.requestHomeSpace
import androidx.xr.compose.spatial.SpatialDialog
import androidx.xr.compose.spatial.SpatialDialogProperties
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.movable
import androidx.xr.compose.subspace.layout.resizable
import androidx.xr.compose.subspace.layout.width
import androidx.xr.compose.unit.DpVolumeSize
import com.nutomic.syncthingandroid.R
import kotlinx.coroutines.launch

private const val TAG = "Spatial"
private const val DIALOG_MIN_WIDTH = 280
private const val DIALOG_MAX_WIDTH = 560
private const val DIALOG_TEXT_MAX_HEIGHT = 480

/** Whether the app is in Full Space, where its UI can be spatial. False on every other device. */
val isSpatialUi: Boolean
    @Composable @ReadOnlyComposable get() = LocalSpatialCapabilities.current.isSpatialUiEnabled

/** The switch between Home Space and Full Space; nothing on devices without Android XR. */
@Composable
fun SpaceModeAction(modifier: Modifier = Modifier) {
    if (!LocalSpatialConfiguration.current.hasXrSpatialFeature) return
    val activity = LocalActivity.current as? ComponentActivity ?: return
    val scope = rememberCoroutineScope()
    val fullSpace = isSpatialUi
    SpaceModeButton(
        fullSpace = fullSpace,
        onToggle = {
            scope.launch {
                val result =
                    if (fullSpace) activity.requestHomeSpace() else activity.requestFullSpace()
                when (result) {
                    is SpaceRequestResult.Success -> Unit
                    is SpaceRequestResult.Error -> Log.e(TAG, "Space request failed", result.cause)
                    else -> Log.w(TAG, "Space request refused: $result")
                }
            }
        },
        modifier = modifier,
    )
}

/** Offers the space the app is not in: icon and word, only the icon below expanded width. */
@Composable
internal fun SpaceModeButton(
    fullSpace: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val icon = if (fullSpace) Icons.Filled.CloseFullscreen else Icons.Filled.OpenInFull
    val label =
        stringResource(if (fullSpace) R.string.fork_home_space else R.string.fork_full_space)
    val wide =
        currentWindowAdaptiveInfoV2()
            .windowSizeClass
            .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
    if (wide) {
        FilledTonalButton(onClick = onToggle, modifier = modifier) {
            Icon(icon, null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
            Text(label)
        }
    } else {
        FilledTonalIconButton(onClick = onToggle, modifier = modifier) { Icon(icon, label) }
    }
}

/**
 * A whole screen: in Full Space a panel the user can move and resize, because the screen would
 * otherwise fill the view; elsewhere just [content]. The content keeps its state across the switch,
 * which matters for a WebView.
 */
@Composable
fun SpatialScreenPanel(width: Dp, height: Dp, content: @Composable () -> Unit) {
    val currentContent by rememberUpdatedState(content)
    val screen = remember { movableContentOf { currentContent() } }
    if (isSpatialUi) {
        Subspace {
            SpatialPanel(
                SubspaceModifier.width(width)
                    .height(height)
                    .resizable(minimumSize = DpVolumeSize(width / 2, height / 2, 0.dp))
                    .movable()
            ) {
                screen()
            }
        }
    } else {
        screen()
    }
}

/**
 * The app's alert dialog. In Full Space a plain dialog would attach to the main window, which
 * Subspace hides, and never show: there it is a spatial dialog.
 */
@Composable
fun AppAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    /** A spatial dialog takes only whether back and taps outside dismiss it. */
    properties: DialogProperties = DialogProperties(),
) {
    if (isSpatialUi) {
        SpatialDialog(
            onDismissRequest = onDismissRequest,
            properties =
                SpatialDialogProperties(
                    dismissOnBackPress = properties.dismissOnBackPress,
                    dismissOnClickOutside = properties.dismissOnClickOutside,
                ),
        ) {
            SpatialDialogBody(confirmButton, modifier, dismissButton, title, text)
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = confirmButton,
            modifier = modifier,
            dismissButton = dismissButton,
            title = title,
            text = text,
            properties = properties,
        )
    }
}

/** What a spatial dialog shows, laid out like an alert dialog. */
@Composable
internal fun SpatialDialogBody(
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.widthIn(min = DIALOG_MIN_WIDTH.dp, max = DIALOG_MAX_WIDTH.dp),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (title != null) {
                ProvideTextStyle(MaterialTheme.typography.headlineSmall) { title() }
            }
            if (text != null) {
                // A spatial dialog measures with no height limit, which a scrolling text cannot
                // take.
                Box(modifier = Modifier.heightIn(max = DIALOG_TEXT_MAX_HEIGHT.dp)) {
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text() }
                }
            }
            FlowRow(
                modifier = Modifier.align(Alignment.End),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                dismissButton?.invoke()
                confirmButton()
            }
        }
    }
}
