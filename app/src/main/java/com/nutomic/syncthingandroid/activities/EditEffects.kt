package com.nutomic.syncthingandroid.activities

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow

/** What a folder or device screen asks the UI around it to do once. */
internal sealed interface EditEvent<out R> {
    /** Opens [intent] for [request]; the result goes back to the screen's ViewModel. */
    data class Launch<R>(val request: R, val intent: Intent) : EditEvent<R>

    data class Open(val intent: Intent) : EditEvent<Nothing>

    data class ShowDeviceId(val name: String, val deviceId: String) : EditEvent<Nothing>

    /** The screen is done; [saved] tells whether the folder or device changed. */
    data class Close(val saved: Boolean) : EditEvent<Nothing>

    /** Back, with nothing to ask. */
    data object Leave : EditEvent<Nothing>
}

/** What the UI around a folder or device screen does for it. */
internal class EditCallbacks<R>(
    val onResult: (R, ActivityResult) -> Unit,
    val onClose: (saved: Boolean) -> Unit,
    val onLeave: () -> Unit,
    val onShowDeviceId: (name: String, deviceId: String) -> Unit = { _, _ -> },
)

/**
 * Runs the [events] of a folder or device screen: one result launcher per request in [requests],
 * and the callbacks of the activity or pane that shows the screen. [whileShown] runs while the
 * screen is resumed.
 */
@Composable
internal fun <R> EditEffects(
    events: Flow<EditEvent<R>>,
    requests: List<R>,
    callbacks: EditCallbacks<R>,
    whileShown: suspend () -> Unit,
) {
    val context = LocalContext.current
    val current by rememberUpdatedState(callbacks)
    val currentWhileShown by rememberUpdatedState(whileShown)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { currentWhileShown() }
    }
    // One launcher per request, always in the same order: the list never changes.
    val launchers = requests.associateWith { request ->
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            current.onResult(request, it)
        }
    }
    LaunchedEffect(events) {
        events.collect { event ->
            when (event) {
                is EditEvent.Launch -> checkNotNull(launchers[event.request]).launch(event.intent)
                is EditEvent.Open -> context.startActivity(event.intent)
                is EditEvent.ShowDeviceId -> current.onShowDeviceId(event.name, event.deviceId)
                is EditEvent.Close -> current.onClose(event.saved)
                EditEvent.Leave -> current.onLeave()
            }
        }
    }
}
