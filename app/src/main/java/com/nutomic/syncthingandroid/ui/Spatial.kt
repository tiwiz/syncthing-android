package com.nutomic.syncthingandroid.ui

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.window.core.layout.WindowSizeClass
import androidx.xr.compose.platform.LocalSpatialCapabilities
import androidx.xr.compose.platform.LocalSpatialConfiguration
import androidx.xr.compose.platform.SpaceRequestResult
import androidx.xr.compose.platform.requestFullSpace
import androidx.xr.compose.platform.requestHomeSpace
import com.nutomic.syncthingandroid.R
import kotlinx.coroutines.launch

private const val TAG = "Spatial"

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
