package com.nutomic.syncthingandroid.service

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.testutil.RunningSyncthingService
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import com.nutomic.syncthingandroid.testutil.StoppedSyncthingService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The app-wide binding to [SyncthingService] that screens observe. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(shadows = [ShadowEnvironmentWithAllFilesAccess::class])
class SyncthingConnectionTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `nothing is bound until someone observes`() = runTest {
        StoppedSyncthingService.install()
        val connection = SyncthingConnection(app, backgroundScope)
        idleMain()

        assertTrue(shadowOf(app).boundServiceConnections.isEmpty())
        assertNull(connection.snapshot.value.service)
    }

    @Test
    fun `observing binds the service and reports its state`() =
        runTest(UnconfinedTestDispatcher()) {
            val service = StoppedSyncthingService.install()
            val connection = SyncthingConnection(app, backgroundScope)

            backgroundScope.launch { connection.snapshot.collect {} }
            idleMain()

            assertSame(service, connection.snapshot.value.service)
            assertEquals(SyncthingService.State.DISABLED, connection.snapshot.value.state)
        }

    @Test
    fun `state changes reach the observers`() =
        runTest(UnconfinedTestDispatcher()) {
            val service = StoppedSyncthingService.install()
            val connection = SyncthingConnection(app, backgroundScope)
            backgroundScope.launch { connection.snapshot.collect {} }
            idleMain()

            RunningSyncthingService.setState(service, SyncthingService.State.ERROR)

            assertEquals(SyncthingService.State.ERROR, connection.snapshot.value.state)
        }

    @Test
    fun `without observers it unbinds after a while, so the service can stop`() =
        runTest(UnconfinedTestDispatcher()) {
            StoppedSyncthingService.install()
            val connection = SyncthingConnection(app, backgroundScope)
            val observer = backgroundScope.launch { connection.snapshot.collect {} }
            idleMain()

            observer.cancel()
            advanceTimeBy(SyncthingConnection.STOP_TIMEOUT_MILLIS - 1)
            assertTrue(shadowOf(app).unboundServiceConnections.isEmpty())

            advanceTimeBy(2)
            idleMain()
            assertEquals(1, shadowOf(app).unboundServiceConnections.size)
        }
}
