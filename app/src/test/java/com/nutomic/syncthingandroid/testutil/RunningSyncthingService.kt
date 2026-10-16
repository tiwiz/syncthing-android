package com.nutomic.syncthingandroid.testutil

import android.os.Looper
import com.nutomic.syncthingandroid.appDependencies
import com.nutomic.syncthingandroid.service.RestApi
import com.nutomic.syncthingandroid.service.SyncthingService
import org.robolectric.Shadows.shadowOf

/**
 * Makes `bindService(SyncthingService)` connect to a service in State.ACTIVE whose [RestApi] has
 * loaded its config from [FakeSyncthing], as if the native binary were running. The service sets
 * its RestApi and state itself only when it launches the binary, so both are set by reflection.
 *
 * Needs the same shadows as [StoppedSyncthingService].
 */
object RunningSyncthingService {

    fun install(syncthing: FakeSyncthing): SyncthingService {
        val service = StoppedSyncthingService.install()
        var available = false
        val api =
            RestApi(
                service,
                service.appDependencies().notificationHandler(),
                syncthing.url,
                API_KEY,
                { available = true },
                {},
            )
        api.readConfigFromRestApi()
        awaitMain { available }
        // Let the follow-up queries of the config load (completion, connections, stats) land.
        awaitMain { syncthing.requests("/rest/stats/device").isNotEmpty() }
        drainMain()

        SyncthingService::class.java.getDeclaredField("api").apply {
            isAccessible = true
            set(service, api)
        }
        setState(service, SyncthingService.State.ACTIVE)
        return service
    }

    /** Moves [service] to [state] the way it does itself, notifying its listeners. */
    fun setState(service: SyncthingService, state: SyncthingService.State) {
        SyncthingService::class
            .java
            .getDeclaredMethod("onServiceStateChange", SyncthingService.State::class.java)
            .apply { isAccessible = true }
            .invoke(service, state)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private const val API_KEY = "fixture-api-key"
}
