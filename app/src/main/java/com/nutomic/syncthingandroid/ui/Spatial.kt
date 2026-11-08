package com.nutomic.syncthingandroid.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.xr.compose.platform.LocalSession
import androidx.xr.compose.platform.LocalSpatialCapabilities
import androidx.xr.compose.platform.LocalSpatialConfiguration
import androidx.xr.scenecore.scene
import com.nutomic.syncthingandroid.R

/** Whether the app is in Full Space, where its UI can be spatial. False on every other device. */
val isSpatialUi: Boolean
    @Composable @ReadOnlyComposable get() = LocalSpatialCapabilities.current.isSpatialUiEnabled

/** The switch between Home Space and Full Space; nothing on devices without Android XR. */
@Composable
fun SpaceModeAction(modifier: Modifier = Modifier) {
    if (!LocalSpatialConfiguration.current.hasXrSpatialFeature) return
    val session = LocalSession.current ?: return
    val fullSpace = isSpatialUi
    SpaceModeButton(
        fullSpace = fullSpace,
        onToggle = {
            if (fullSpace) session.scene.requestHomeSpace() else session.scene.requestFullSpace()
        },
        modifier = modifier,
    )
}

/** Offers the space the app is not in. */
@Composable
internal fun SpaceModeButton(
    fullSpace: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalButton(onClick = onToggle, modifier = modifier) {
        Icon(if (fullSpace) Icons.Filled.CloseFullscreen else Icons.Filled.OpenInFull, null)
        Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
        Text(stringResource(if (fullSpace) R.string.fork_home_space else R.string.fork_full_space))
    }
}
