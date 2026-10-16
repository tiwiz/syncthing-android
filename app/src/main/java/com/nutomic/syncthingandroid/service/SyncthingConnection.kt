package com.nutomic.syncthingandroid.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.nutomic.syncthingandroid.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn

/** What screens see of [SyncthingService]: the service while bound, and its state. */
data class ServiceSnapshot(val service: SyncthingService?, val state: SyncthingService.State?) {
    /** The REST API, or null while not bound or while Syncthing does not run. */
    val api: RestApi?
        get() = service?.api

    companion object {
        val UNBOUND = ServiceSnapshot(null, null)
    }
}

/**
 * The app-wide binding to [SyncthingService]. It binds while someone observes [snapshot] and
 * unbinds [STOP_TIMEOUT_MILLIS] after the last observer is gone (a rotation does not rebind), so
 * exiting the app still lets the service stop.
 */
@Singleton
class SyncthingConnection
@Inject
constructor(
    @ApplicationContext context: Context,
    @ApplicationScope scope: CoroutineScope,
) {

    val snapshot: StateFlow<ServiceSnapshot> = callbackFlow {
        var bound: SyncthingService? = null
        val listener = SyncthingService.OnServiceStateChangeListener { state ->
            trySend(ServiceSnapshot(bound, state))
        }
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    val service = (binder as SyncthingServiceBinder).service
                    bound = service
                    // Sends the current state right away.
                    service.registerOnServiceStateChangeListener(listener)
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    bound = null
                    trySend(ServiceSnapshot.UNBOUND)
                }
            }
        context.bindService(
            Intent(context, SyncthingService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        awaitClose {
            bound?.unregisterOnServiceStateChangeListener(listener)
            context.unbindService(connection)
        }
    }
        .stateIn(
            scope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            ServiceSnapshot.UNBOUND,
        )

    companion object {
        /** How long the binding outlives its last observer. */
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
