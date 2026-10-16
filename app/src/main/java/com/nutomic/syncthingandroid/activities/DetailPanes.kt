package com.nutomic.syncthingandroid.activities

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.nutomic.syncthingandroid.fragments.DeviceIdDialogFragment
import com.nutomic.syncthingandroid.service.RestApi
import com.nutomic.syncthingandroid.util.ConfigRouter
import kotlinx.coroutines.CoroutineScope

/** What the detail pane shows next to a list on a large window. */
@Stable
internal sealed interface Detail {
    /** "folder:<id>" or "device:<id>": tells details apart and survives recreation. */
    val key: String

    class FolderDetail(override val key: String, val controller: FolderEditController) : Detail

    class DeviceDetail(override val key: String, val controller: DeviceEditController) : Detail
}

/**
 * The detail pane of the main screen on large windows: which folder or device it shows, and the
 * host its [FolderEditController] or [DeviceEditController] runs in. Create it while [activity] is
 * constructed, as it registers result launchers.
 */
internal class DetailPanes(
    private val activity: SyncthingActivity,
    private val preferences: () -> SharedPreferences,
    /** Called when a folder or device was saved or removed. */
    private val onChanged: () -> Unit,
) {
    var detail by mutableStateOf<Detail?>(null)
        private set

    /** Whether the window has room for the pane; reported by the screen. */
    var twoPanes by mutableStateOf(false)

    /** A detail to open once the current one, with unsaved changes, is discarded. */
    private var pending: String? = null

    private val configRouter by lazy { ConfigRouter(activity) }

    private val folderLaunchers =
        FolderRequest.entries.associateWith { request ->
            activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                (detail as? Detail.FolderDetail)?.controller?.onResult(request, it)
            }
        }

    private val deviceLaunchers =
        DeviceRequest.entries.associateWith { request ->
            activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                (detail as? Detail.DeviceDetail)?.controller?.onResult(request, it)
            }
        }

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
            detail = create(key, null)
        }
        return true
    }

    /** Back in the pane: its screen asks about unsaved changes, else closes. */
    fun back() {
        when (val current = detail) {
            is Detail.FolderDetail -> current.controller.actions.onBack()
            is Detail.DeviceDetail -> current.controller.actions.onBack()
            null -> Unit
        }
    }

    fun onApiAvailable(restApi: RestApi) {
        (detail as? Detail.DeviceDetail)?.controller?.onApiAvailable(restApi)
    }

    fun saveState(outState: Bundle) {
        val current = detail ?: return
        val state = Bundle()
        when (current) {
            is Detail.FolderDetail -> current.controller.saveState(state)
            is Detail.DeviceDetail -> current.controller.saveState(state)
        }
        outState.putString(STATE_KEY, current.key)
        outState.putBundle(STATE_DETAIL, state)
    }

    fun restoreState(savedState: Bundle?) {
        val key = savedState?.getString(STATE_KEY) ?: return
        detail = create(key, savedState.getBundle(STATE_DETAIL))
    }

    private fun close() {
        detail = null
        pending?.let {
            pending = null
            open(it)
        }
    }

    private fun create(key: String, savedState: Bundle?): Detail? {
        val id = key.substringAfter(':')
        return when {
            key.startsWith(FOLDER) ->
                FolderEditController.create(
                        activity,
                        preferences(),
                        configRouter,
                        folderHost,
                        FolderEditArgs(create = false, folderId = id),
                        savedState,
                    )
                    ?.let { Detail.FolderDetail(key, it) }
            key.startsWith(DEVICE) ->
                DeviceEditController.create(
                        activity,
                        preferences(),
                        configRouter,
                        deviceHost,
                        DeviceEditArgs(create = false, deviceId = id),
                        savedState,
                    )
                    ?.also { controller -> activity.api?.let(controller::onApiAvailable) }
                    ?.let { Detail.DeviceDetail(key, it) }
            else -> null
        }
    }

    private fun Detail.hasChanges(): Boolean =
        when (this) {
            is Detail.FolderDetail -> controller.form?.changed == true
            is Detail.DeviceDetail -> controller.form?.changed == true
        }

    private fun Detail.askDiscard() =
        when (this) {
            is Detail.FolderDetail -> controller.actions.onDialog(FolderDialog.DISCARD)
            is Detail.DeviceDetail -> controller.actions.onDialog(DeviceDialog.DISCARD)
        }

    private fun closed(saved: Boolean) {
        close()
        if (saved) onChanged()
    }

    private val folderHost =
        object : FolderEditHost {
            override val restApi: RestApi?
                get() = activity.api

            override val scope: CoroutineScope
                get() = activity.lifecycleScope

            override fun launch(request: FolderRequest, intent: Intent) =
                checkNotNull(folderLaunchers[request]).launch(intent)

            override fun open(intent: Intent) = activity.startActivity(intent)

            override fun close(saved: Boolean) = closed(saved)

            override fun leave() = close()
        }

    private val deviceHost =
        object : DeviceEditHost {
            override val restApi: RestApi?
                get() = activity.api

            override fun launch(request: DeviceRequest, intent: Intent) =
                checkNotNull(deviceLaunchers[request]).launch(intent)

            override fun open(intent: Intent) = activity.startActivity(intent)

            override fun showDeviceId(name: String, deviceId: String) =
                DeviceIdDialogFragment.show(activity.supportFragmentManager, name, deviceId, false)

            override fun close(saved: Boolean) = closed(saved)

            override fun leave() = close()
        }

    companion object {
        const val FOLDER = "folder:"
        const val DEVICE = "device:"
        private const val STATE_KEY = "detailKey"
        private const val STATE_DETAIL = "detailState"
    }
}
