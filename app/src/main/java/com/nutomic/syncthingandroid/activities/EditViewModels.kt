package com.nutomic.syncthingandroid.activities

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.result.ActivityResult
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nutomic.syncthingandroid.service.RestApi
import com.nutomic.syncthingandroid.service.SyncthingConnection
import com.nutomic.syncthingandroid.util.ConfigRouter
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

// The folder and device screens' ViewModels: they keep the screen's controller across
// configuration changes, save it for process death, and turn what it asks of its host into
// events for the activity or pane that shows it (EditEffects).

/** Key of a controller's saved state in the ViewModel's SavedStateHandle. */
private const val STATE_CONTROLLER = "controller"

/** A folder screen; [controller] is null when the folder to edit is gone. */
@HiltViewModel(assistedFactory = FolderEditViewModel.Factory::class)
internal class FolderEditViewModel
@AssistedInject
constructor(
    @ApplicationContext context: Context,
    preferences: SharedPreferences,
    private val connection: SyncthingConnection,
    savedState: SavedStateHandle,
    @Assisted args: FolderEditArgs,
) : ViewModel() {

    private val eventChannel = Channel<EditEvent<FolderRequest>>(Channel.BUFFERED)
    val events: Flow<EditEvent<FolderRequest>> = eventChannel.receiveAsFlow()

    private val host =
        object : FolderEditHost {
            override val restApi: RestApi?
                get() = connection.snapshot.value.api

            override val scope: CoroutineScope
                get() = viewModelScope

            override fun launch(request: FolderRequest, intent: Intent) {
                eventChannel.trySend(EditEvent.Launch(request, intent))
            }

            override fun open(intent: Intent) {
                eventChannel.trySend(EditEvent.Open(intent))
            }

            override fun close(saved: Boolean) {
                eventChannel.trySend(EditEvent.Close(saved))
            }

            override fun leave() {
                eventChannel.trySend(EditEvent.Leave)
            }
        }

    val controller: FolderEditController? =
        FolderEditController.create(
            context,
            preferences,
            ConfigRouter(context),
            host,
            args,
            savedState.get<Bundle>(STATE_CONTROLLER),
        )

    init {
        controller?.let { saved ->
            savedState.setSavedStateProvider(STATE_CONTROLLER) { Bundle().also(saved::saveState) }
        }
        // Keeps the binding to Syncthing while the screen lives, so saving reaches the REST API.
        viewModelScope.launch { connection.snapshot.collect {} }
    }

    fun onResult(request: FolderRequest, result: ActivityResult) =
        controller?.onResult(request, result)

    @AssistedFactory
    interface Factory {
        fun create(args: FolderEditArgs): FolderEditViewModel
    }
}

/** A device screen; [controller] is null when the device to edit is gone. */
@HiltViewModel(assistedFactory = DeviceEditViewModel.Factory::class)
internal class DeviceEditViewModel
@AssistedInject
constructor(
    @ApplicationContext context: Context,
    preferences: SharedPreferences,
    private val connection: SyncthingConnection,
    savedState: SavedStateHandle,
    @Assisted args: DeviceEditArgs,
) : ViewModel() {

    private val eventChannel = Channel<EditEvent<DeviceRequest>>(Channel.BUFFERED)
    val events: Flow<EditEvent<DeviceRequest>> = eventChannel.receiveAsFlow()

    private val host =
        object : DeviceEditHost {
            override val restApi: RestApi?
                get() = connection.snapshot.value.api

            override fun launch(request: DeviceRequest, intent: Intent) {
                eventChannel.trySend(EditEvent.Launch(request, intent))
            }

            override fun open(intent: Intent) {
                eventChannel.trySend(EditEvent.Open(intent))
            }

            override fun showDeviceId(name: String, deviceId: String) {
                eventChannel.trySend(EditEvent.ShowDeviceId(name, deviceId))
            }

            override fun close(saved: Boolean) {
                eventChannel.trySend(EditEvent.Close(saved))
            }

            override fun leave() {
                eventChannel.trySend(EditEvent.Leave)
            }
        }

    val controller: DeviceEditController? =
        DeviceEditController.create(
            context,
            preferences,
            ConfigRouter(context),
            host,
            args,
            savedState.get<Bundle>(STATE_CONTROLLER),
        )

    init {
        controller?.let { saved ->
            savedState.setSavedStateProvider(STATE_CONTROLLER) { Bundle().also(saved::saveState) }
            // The connection and nearby devices show once Syncthing's REST API is there.
            viewModelScope.launch {
                saved.onApiAvailable(connection.snapshot.map { it.api }.filterNotNull().first())
            }
        }
        viewModelScope.launch { connection.snapshot.collect {} }
    }

    fun onResult(request: DeviceRequest, result: ActivityResult) =
        controller?.onResult(request, result)

    @AssistedFactory
    interface Factory {
        fun create(args: DeviceEditArgs): DeviceEditViewModel
    }
}
