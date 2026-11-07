package com.nutomic.syncthingandroid.activities

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModelProvider
import dagger.hilt.android.lifecycle.withCreationCallback

/**
 * The folder or device open in the main screen's detail pane, kept by [MainViewModel].
 *
 * @property key "folder:<id>" or "device:<id>": tells details apart and survives recreation.
 * @property opened numbers the opening, so each opening gets ViewModels of its own.
 */
internal data class DetailSelection(val key: String, val opened: Int) {

    /** Names this opening's ViewModels in the activity's store. */
    val viewModelKey: String
        get() = "$key#$opened"

    companion object {
        const val FOLDER = "folder:"
        const val DEVICE = "device:"
    }
}

/** What the detail pane shows next to a list on a large window. */
@Stable
internal sealed interface Detail {
    /** "folder:<id>" or "device:<id>", as in [DetailSelection.key]. */
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
 * The detail for [selection], with the opening's ViewModels from this activity's store: they
 * survive configuration changes, and a reopened folder starts from the saved config, not from
 * discarded edits. Null when the folder or device cannot be loaded.
 */
internal fun ComponentActivity.detailOf(
    selection: DetailSelection,
    callbacks: PaneCallbacks,
): Detail? {
    val key = selection.key
    val id = key.substringAfter(':')
    return when {
        key.startsWith(DetailSelection.FOLDER) -> {
            val extras =
                defaultViewModelCreationExtras.withCreationCallback<FolderEditViewModel.Factory> {
                    it.create(FolderEditArgs(create = false, folderId = id))
                }
            ViewModelProvider.create(viewModelStore, defaultViewModelProviderFactory, extras)[
                    selection.viewModelKey, FolderEditViewModel::class]
                .takeIf { it.controller != null }
                ?.let { Detail.FolderDetail(key, it, callbacks) }
        }
        key.startsWith(DetailSelection.DEVICE) -> {
            val extras =
                defaultViewModelCreationExtras.withCreationCallback<DeviceEditViewModel.Factory> {
                    it.create(DeviceEditArgs(create = false, deviceId = id))
                }
            ViewModelProvider.create(viewModelStore, defaultViewModelProviderFactory, extras)[
                    selection.viewModelKey, DeviceEditViewModel::class]
                .takeIf { it.controller != null }
                ?.let { Detail.DeviceDetail(key, it, callbacks) }
        }
        else -> null
    }
}

/** Whether the pane's screen has unsaved changes. */
internal fun Detail.hasChanges(): Boolean =
    when (this) {
        is Detail.FolderDetail -> viewModel.controller?.form?.changed == true
        is Detail.DeviceDetail -> viewModel.controller?.form?.changed == true
    }

/** Asks the pane's screen to discard its unsaved changes. */
internal fun Detail.askDiscard() {
    when (this) {
        is Detail.FolderDetail -> viewModel.controller?.actions?.onDialog(FolderDialog.DISCARD)
        is Detail.DeviceDetail -> viewModel.controller?.actions?.onDialog(DeviceDialog.DISCARD)
    }
}

/** Back in the pane: its screen asks about unsaved changes, else closes. */
internal fun Detail.back() {
    when (this) {
        is Detail.FolderDetail -> viewModel.controller?.actions?.onBack()
        is Detail.DeviceDetail -> viewModel.controller?.actions?.onBack()
    }
}
