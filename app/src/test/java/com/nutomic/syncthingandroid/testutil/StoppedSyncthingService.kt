package com.nutomic.syncthingandroid.testutil

import android.app.Application
import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import com.nutomic.syncthingandroid.service.SyncthingService
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/**
 * Makes `bindService(SyncthingService)` connect to a real service instance that was created but
 * never started: Syncthing itself is not running (state DISABLED), like right after app start
 * when the run conditions say no.
 *
 * Needs [ShadowEnvironmentWithAllFilesAccess], or the service stops itself in `onCreate`.
 */
object StoppedSyncthingService {

    fun install(): SyncthingService {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val service = Robolectric.buildService(SyncthingService::class.java).create().get()
        shadowOf(app)
            .setComponentNameAndServiceForBindService(
                ComponentName(app, SyncthingService::class.java),
                service.onBind(null),
            )
        return service
    }
}
