package com.nutomic.syncthingandroid.activities

import android.os.Bundle
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import com.nutomic.syncthingandroid.fragments.DeviceIdDialogFragment
import dagger.hilt.android.lifecycle.withCreationCallback

/** What the detail pane shows next to a list on a large window. */
@Stable
internal sealed interface Detail {
    /** "folder:<id>" or "device:<id>": tells details apart and survives recreation. */
    val key: String

    /** What the pane's screen does when it closes or goes back. */
    val callbacks: PaneCallbacks

    class FolderDetail(
        override val key: String,
        val viewModel: FolderEditViewModel,
        override val callbacks: PaneCallbacks,
    ) : Detail

    class DeviceDetail(
        override val key: String,
        val viewModel: DeviceEditViewModel,
        override val callbacks: PaneCallbacks,
    ) : Detail
}

/** What the activity does for the screen in the detail pane. */
internal class PaneCallbacks(
    val onClose: (saved: Boolean) -> Unit,
    val onLeave: () -> Unit,
    val onShowDeviceId: (name: String, deviceId: String) -> Unit,
)

/**
 * The detail pane of the main screen on large windows: which folder or device it shows. Each
 * opening gets ViewModels of its own in [activity]'s store, so they survive configuration changes
 * and a reopened folder starts from the saved config, not from discarded edits.
 */
internal class DetailPanes(
    private val activity: SyncthingActivity,
    /** Called when a folder or device was saved or removed. */
    onChanged: () -> Unit,
) {
    var detail by mutableStateOf<Detail?>(null)
        private set

    /** How far a back gesture closing the pane has gone, 0 to 1. */
    var backProgress by mutableFloatStateOf(0f)

    /** Whether the window has room for the pane; reported by the screen. */
    var twoPanes by mutableStateOf(false)

    /** A detail to open once the current one, with unsaved changes, is discarded. */
    private var pending: String? = null

    /** How many details were opened: names each opening's ViewModels. */
    private var opened = 0

    private val callbacks =
        PaneCallbacks(
            onClose = { saved ->
                close()
                if (saved) onChanged()
            },
            onLeave = ::close,
            onShowDeviceId = { name, deviceId ->
                DeviceIdDialogFragment.show(activity.supportFragmentManager, name, deviceId, false)
            },
        )

    /**
     * Shows the folder or device [key] in the pane; false when the window has no room for it. With
     * unsaved changes in the pane, asks to discard them first.
     */
    fun open(key: String): Boolean {
        if (!twoPanes) return false
        val current = detail
        if (current != null && current.hasChanges()) {
            pending = key
            current.askDiscard()
        } else {
            opened++
            detail = create(key)
        }
        return true
    }

    /** Back in the pane: its screen asks about unsaved changes, else closes. */
    fun back() {
        when (val current = detail) {
            is Detail.FolderDetail -> current.viewModel.controller?.actions?.onBack()
            is Detail.DeviceDetail -> current.viewModel.controller?.actions?.onBack()
            null -> Unit
        }
    }

    fun saveState(outState: Bundle) {
        val current = detail ?: return
        outState.putString(STATE_KEY, current.key)
        outState.putInt(STATE_OPENED, opened)
    }

    fun restoreState(savedState: Bundle?) {
        val key = savedState?.getString(STATE_KEY) ?: return
        opened = savedState.getInt(STATE_OPENED)
        detail = create(key)
    }

    private fun close() {
        detail = null
        pending?.let {
            pending = null
            open(it)
        }
    }

    private fun create(key: String): Detail? {
        val id = key.substringAfter(':')
        val viewModelKey = "$key#$opened"
        return when {
            key.startsWith(FOLDER) -> {
                val extras =
                    activity.defaultViewModelCreationExtras.withCreationCallback<
                        FolderEditViewModel.Factory
                    > {
                        it.create(FolderEditArgs(create = false, folderId = id))
                    }
                ViewModelProvider.create(
                        activity.viewModelStore,
                        activity.defaultViewModelProviderFactory,
                        extras,
                    )[viewModelKey, FolderEditViewModel::class]
                    .takeIf { it.controller != null }
                    ?.let { Detail.FolderDetail(key, it, callbacks) }
            }
            key.startsWith(DEVICE) -> {
                val extras =
                    activity.defaultViewModelCreationExtras.withCreationCallback<
                        DeviceEditViewModel.Factory
                    > {
                        it.create(DeviceEditArgs(create = false, deviceId = id))
                    }
                ViewModelProvider.create(
                        activity.viewModelStore,
                        activity.defaultViewModelProviderFactory,
                        extras,
                    )[viewModelKey, DeviceEditViewModel::class]
                    .takeIf { it.controller != null }
                    ?.let { Detail.DeviceDetail(key, it, callbacks) }
            }
            else -> null
        }
    }

    private fun Detail.hasChanges(): Boolean =
        when (this) {
            is Detail.FolderDetail -> viewModel.controller?.form?.changed == true
            is Detail.DeviceDetail -> viewModel.controller?.form?.changed == true
        }

    private fun Detail.askDiscard() =
        when (this) {
            is Detail.FolderDetail -> viewModel.controller?.actions?.onDialog(FolderDialog.DISCARD)
            is Detail.DeviceDetail -> viewModel.controller?.actions?.onDialog(DeviceDialog.DISCARD)
        }

    companion object {
        const val FOLDER = "folder:"
        const val DEVICE = "device:"
        private const val STATE_KEY = "detailKey"
        private const val STATE_OPENED = "detailOpened"
    }
}
