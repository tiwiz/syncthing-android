package com.nutomic.syncthingandroid.settings

import android.app.Application
import android.os.Looper
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.SavedStateHandle
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.service.Constants
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
import org.junit.Assert.assertTrue
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

    private fun TestScope.userInterfaceViewModel() =
        UserInterfaceViewModel(
            app,
            boundConnection(),
            backgroundScope,
            UnconfinedTestDispatcher(testScheduler),
        )

    @Test
    fun `the app theme goes to the web GUI while Syncthing is stopped`() =
        runTest(UnconfinedTestDispatcher()) {
            ConfigFixture.install(app) { it.replace("<apikey>", "<user>me</user><apikey>") }
            val viewModel = userInterfaceViewModel()

            viewModel.syncWebGuiTheme(AppCompatDelegate.MODE_NIGHT_YES.toString())
            assertTrue(Constants.getConfigFile(app).readText().contains("<theme>dark</theme>"))

            viewModel.syncWebGuiTheme(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM.toString())
            assertTrue(Constants.getConfigFile(app).readText().contains("<theme>default</theme>"))
        }

    @Test
    fun `without a web GUI user the theme is not written`() =
        runTest(UnconfinedTestDispatcher()) {
            // Syncthing leaves out <user> while it is empty, as in the fixture.
            userInterfaceViewModel().syncWebGuiTheme(AppCompatDelegate.MODE_NIGHT_YES.toString())

            // CHARACTERIZATION: ConfigXml.updateGui throws on the missing user, and the theme sync
            // only logs it (docs/TECH-DEBT.md).
            assertTrue(Constants.getConfigFile(app).readText().contains("<theme>default</theme>"))
        }

    @Test
    fun `an export writes the backup and closes the screen`() =
        runTest(UnconfinedTestDispatcher()) {
            // An import also needs the native binary to clean the index, so only export runs here.
            val viewModel =
                ImportExportViewModel(
                    app,
                    boundConnection(),
                    PreferenceManager.getDefaultSharedPreferences(app),
                    backgroundScope,
                    UnconfinedTestDispatcher(testScheduler),
                )

            viewModel.exportConfig()

            assertEquals(SettingsEvent.CLOSE, viewModel.events.first())
            assertEquals(
                app.getString(R.string.config_export_successful_no_path),
                ShadowToast.getTextOfLatestToast(),
            )
        }

    @Test
    fun `about names the app version and an unknown core version while stopped`() =
        runTest(UnconfinedTestDispatcher()) {
            val viewModel =
                AboutViewModel(app, boundConnection(), UnconfinedTestDispatcher(testScheduler))
            backgroundScope.launch { viewModel.state.collect {} }

            val state =
                viewModel.state.first { it.coreVersion != app.getString(R.string.state_loading) }
            assertTrue(state.appVersion, state.appVersion.startsWith("v"))
            assertEquals(app.getString(R.string.state_unknown), state.coreVersion)
        }
}
