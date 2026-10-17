package com.nutomic.syncthingandroid.settings

import android.app.Application
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.service.SyncthingConnection
import com.nutomic.syncthingandroid.service.SyncthingService.HttpsCertReplaceResult
import com.nutomic.syncthingandroid.testutil.ConfigFixture
import com.nutomic.syncthingandroid.testutil.Fixtures
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import com.nutomic.syncthingandroid.testutil.StoppedSyncthingService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/** The settings ViewModels on their own, with the service bound through [SyncthingConnection]. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(shadows = [ShadowEnvironmentWithAllFilesAccess::class])
class SettingsViewModelsTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun setUp() {
        ConfigFixture.install(app)
        StoppedSyncthingService.install()
    }

    /** A connection that is bound for the rest of the test. */
    private fun TestScope.boundConnection(): SyncthingConnection =
        SyncthingConnection(app, backgroundScope).also { connection ->
            backgroundScope.launch { connection.snapshot.collect {} }
            shadowOf(Looper.getMainLooper()).idle()
        }

    @Test
    fun `the custom certificate screen comes back with its picked files and failure`() =
        runTest(UnconfinedTestDispatcher()) {
            val saved =
                SavedStateHandle(
                    mapOf(
                        "cert_name" to "leaf-cert.pem",
                        "cert_bytes" to Fixtures.bytes("certs/leaf-cert.pem"),
                        "key_name" to "leaf-key.pem",
                        "key_bytes" to Fixtures.bytes("certs/leaf-key.pem"),
                        "failure_outcome" to HttpsCertReplaceResult.FAILED_NOT_ONLINE.name,
                    )
                )

            val viewModel =
                CustomCertificateViewModel(
                    app,
                    boundConnection(),
                    saved,
                    UnconfinedTestDispatcher(testScheduler),
                )

            val state = viewModel.uiState.value
            assertEquals("leaf-cert.pem", state.certName)
            assertEquals("leaf-key.pem", state.keyName)
            assertEquals(
                ApplyFailure(HttpsCertReplaceResult.FAILED_NOT_ONLINE, null),
                state.applyFailure,
            )
            assertNotNull("validated again from the saved bytes", state.validation)
        }

    @Test
    fun `a failure saved by an older version restores as no failure`() =
        runTest(UnconfinedTestDispatcher()) {
            val saved = SavedStateHandle(mapOf("failure_outcome" to "NO_LONGER_EXISTS"))

            val viewModel =
                CustomCertificateViewModel(
                    app,
                    boundConnection(),
                    saved,
                    UnconfinedTestDispatcher(testScheduler),
                )

            assertNull(viewModel.uiState.value.applyFailure)
        }

    @Test
    fun `a failed import says so and asks for the log`() =
        runTest(UnconfinedTestDispatcher()) {
            val viewModel =
                ImportExportViewModel(
                    app,
                    boundConnection(),
                    app.getSharedPreferences("test", 0),
                    backgroundScope,
                    UnconfinedTestDispatcher(testScheduler),
                )

            // No backup zip in the fixture.
            viewModel.importConfig()

            assertEquals(SettingsEvent.OPEN_LOG, viewModel.events.first())
            assertEquals(
                app.getString(com.nutomic.syncthingandroid.R.string.config_import_failed_no_path),
                ShadowToast.getTextOfLatestToast(),
            )
        }
}
