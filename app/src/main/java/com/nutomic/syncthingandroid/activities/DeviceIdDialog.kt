package com.nutomic.syncthingandroid.activities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import androidx.core.os.bundleOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.ui.AppAlertDialog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the device ID dialog shows. */
internal data class DeviceIdRequest(
    val deviceName: String,
    val deviceId: String,
    val isCurrentDevice: Boolean,
)

/** Which device ID the screen shows, kept across configuration changes and process death. */
internal class DeviceIdDialogState(private val savedState: SavedStateHandle) {

    private val state =
        MutableStateFlow(
            savedState.get<Bundle>(STATE_REQUEST)?.let {
                DeviceIdRequest(
                    it.getString(ARG_DEVICE_NAME).orEmpty(),
                    it.getString(ARG_DEVICE_ID).orEmpty(),
                    it.getBoolean(ARG_IS_CURRENT_DEVICE),
                )
            }
        )

    /** The device ID to show, or null with no dialog. */
    val request: StateFlow<DeviceIdRequest?> = state.asStateFlow()

    fun show(request: DeviceIdRequest) {
        savedState[STATE_REQUEST] =
            bundleOf(
                ARG_DEVICE_NAME to request.deviceName,
                ARG_DEVICE_ID to request.deviceId,
                ARG_IS_CURRENT_DEVICE to request.isCurrentDevice,
            )
        state.value = request
    }

    fun dismiss() {
        savedState.remove<Bundle>(STATE_REQUEST)
        state.value = null
    }

    private companion object {
        const val STATE_REQUEST = "device_id_request"
        const val ARG_DEVICE_NAME = "device_name"
        const val ARG_DEVICE_ID = "device_id"
        const val ARG_IS_CURRENT_DEVICE = "is_current_device"
    }
}

/**
 * Shows the dialog [state] asks for. Part of the screen, so it also shows in Full Space, where it
 * has to sit inside a panel: Subspace hides the main window and whatever is anchored to it.
 */
@Composable
internal fun DeviceIdDialogHost(state: DeviceIdDialogState) {
    val request by state.request.collectAsStateWithLifecycle()
    request?.let { DeviceIdDialog(it, onDismiss = state::dismiss) }
}

private fun generateQrCode(deviceId: String): Bitmap {
    val qrSize = 232
    val black = 0xFF000000
    val white = 0x00000000

    val bitMatrix = MultiFormatWriter().encode(deviceId, BarcodeFormat.QR_CODE, qrSize, qrSize)
    val bitMap = createBitmap(bitMatrix.width, bitMatrix.height, Bitmap.Config.ARGB_8888)

    for (x in 0 until qrSize) {
        for (y in 0 until qrSize) {
            val pixel = if (bitMatrix[x, y]) black else white
            bitMap[x, y] = pixel.toInt()
        }
    }

    return bitMap
}

private fun copyDeviceId(context: Context, id: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    val clip = ClipData.newPlainText(context.getString(R.string.device_id), id)
    clipboard.setPrimaryClip(clip)

    // Android 13+ shows a system confirmation automatically
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, R.string.device_id_copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }
}

private fun shareDeviceId(context: Context, deviceId: String) {
    val shareIntent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, deviceId)
        }

    context.startActivity(
        Intent.createChooser(shareIntent, context.getString(R.string.share_device_id_chooser))
    )
}

@Composable
internal fun DeviceIdDialog(
    request: DeviceIdRequest,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val deviceId = request.deviceId
    val qrCode = remember(deviceId) { generateQrCode(deviceId) }
    val onCopy = { copyDeviceId(context, deviceId) }
    val onShare = { shareDeviceId(context, deviceId) }
    val landscape = isLandscape()
    AppAlertDialog(
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier =
            modifier.let {
                if (!landscape) it.widthIn(max = 460.dp).fillMaxWidth(DIALOG_WIDTH_FRACTION) else it
            },
        onDismissRequest = onDismiss,
        title = {
            if (landscape) {
                Row(Modifier.fillMaxWidth()) {
                    DialogTitle(request.deviceName, request.isCurrentDevice, Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, stringResource(R.string.close_device_id))
                    }
                }
            } else {
                DialogTitle(request.deviceName, request.isCurrentDevice)
            }
        },
        text = {
            if (landscape) {
                LandscapeDialogContent(deviceId, qrCode, onCopy, onShare)
            } else {
                PortraitDialogContent(deviceId, qrCode, onCopy, onShare)
            }
        },
        confirmButton = {
            if (!landscape) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.finish)) }
            }
        },
    )
}

private const val DIALOG_WIDTH_FRACTION = 0.9f

@Composable
@ReadOnlyComposable
private fun isLandscape(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
}

@Composable
private fun DialogTitle(
    deviceName: String,
    isCurrentDevice: Boolean,
    modifier: Modifier = Modifier,
) {
    val thisDeviceText = stringResource(R.string.this_device)

    Column(modifier) {
        Text(stringResource(R.string.device_id))
        Spacer(Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                    contentDescription = deviceName
                    if (isCurrentDevice) {
                        stateDescription = thisDeviceText
                    }
                },
        ) {
            Text(
                text = deviceName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (isCurrentDevice) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "•",
                    style = MaterialTheme.typography.titleMedium,
                    softWrap = false,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = thisDeviceText,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun PortraitDialogContent(
    deviceId: String,
    qrCode: Bitmap,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val windowInfo = LocalWindowInfo.current
    val containerHeightDp = windowInfo.containerDpSize.height
    val qrMaxHeight = minOf(containerHeightDp * 0.30f, 280.dp)

    Column(modifier) {
        // Tight corners: rounder ones would cut into the QR code's quiet zone.
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.White,
            modifier = Modifier.heightIn(min = 160.dp, max = qrMaxHeight).fillMaxWidth(),
        ) {
            Image(
                bitmap = qrCode.asImageBitmap(),
                contentDescription = stringResource(R.string.device_id),
                Modifier.fillMaxHeight(),
            )
        }
        Spacer(Modifier.height(16.dp))
        Surface(
            shape = RoundedCornerShape(CARD_CORNER.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Text(deviceId, modifier = Modifier.padding(16.dp))
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onCopy, Modifier.weight(1f)) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = stringResource(R.string.copy),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.copy))
            }
            FilledTonalButton(onShare, Modifier.weight(1f)) {
                Icon(
                    imageVector = Icons.Outlined.Share,
                    contentDescription = stringResource(R.string.share_title),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.share_title))
            }
        }
    }
}

@Composable
private fun LandscapeDialogContent(
    deviceId: String,
    qrCode: Bitmap,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().heightIn(max = 250.dp)) {
        // Tight corners: rounder ones would cut into the QR code's quiet zone.
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.White,
            modifier = Modifier.fillMaxHeight().weight(1f),
        ) {
            Image(
                bitmap = qrCode.asImageBitmap(),
                contentDescription = stringResource(R.string.device_id),
                Modifier.fillMaxHeight(),
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Surface(
                shape = RoundedCornerShape(CARD_CORNER.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Text(deviceId, modifier = Modifier.padding(16.dp))
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onCopy, Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = stringResource(R.string.copy),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.copy))
                }
                FilledTonalButton(onShare, Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.Outlined.Share,
                        contentDescription = stringResource(R.string.share_title),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.share_title))
                }
            }
        }
    }
}
