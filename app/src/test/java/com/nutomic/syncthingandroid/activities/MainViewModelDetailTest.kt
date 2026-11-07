package com.nutomic.syncthingandroid.activities

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.service.SyncthingConnection
import com.nutomic.syncthingandroid.testutil.ConfigFixture
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import com.nutomic.syncthingandroid.testutil.StoppedSyncthingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The detail pane's selection in [MainViewModel] and its [SavedStateHandle] round trip. */
@RunWith(AndroidJUnit4::class)
@Config(shadows = [ShadowEnvironmentWithAllFilesAccess::class])
class MainViewModelDetailTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(Job())

    @Before
    fun setUp() {
        ConfigFixture.install(app)
        StoppedSyncthingService.install()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun viewModel(handle: SavedStateHandle) =
        MainViewModel(
            app,
            PreferenceManager.getDefaultSharedPreferences(app),
            SyncthingConnection(app, scope),
            handle,
        )

    /** What the activity saves and gets back after process death. */
    private fun SavedStateHandle.roundTrip() =
        SavedStateHandle(keys().associateWith { get<Any>(it) })

    @Test
    fun `nothing is open in a new screen`() {
        assertNull(viewModel(SavedStateHandle()).detail.value)
    }

    @Test
    fun `each opening gets a number of its own`() {
        val viewModel = viewModel(SavedStateHandle())

        viewModel.openDetail(DetailSelection.FOLDER + "abc")
        assertEquals(DetailSelection("folder:abc", 1), viewModel.detail.value)
        assertEquals("folder:abc#1", viewModel.detail.value?.viewModelKey)

        viewModel.openDetail(DetailSelection.DEVICE + "XYZ")
        assertEquals(DetailSelection("device:XYZ", 2), viewModel.detail.value)
    }

    @Test
    fun `the selection and its opening number survive a SavedStateHandle round trip`() {
        val handle = SavedStateHandle()
        val viewModel = viewModel(handle)
        viewModel.openDetail(DetailSelection.FOLDER + "abc")
        viewModel.openDetail(DetailSelection.FOLDER + "abc")

        val restored = viewModel(handle.roundTrip())

        assertEquals(DetailSelection("folder:abc", 2), restored.detail.value)
    }

    @Test
    fun `closing clears the selection, also after a round trip`() {
        val handle = SavedStateHandle()
        val viewModel = viewModel(handle)
        viewModel.openDetail(DetailSelection.DEVICE + "XYZ")

        viewModel.closeDetail()

        assertNull(viewModel.detail.value)
        assertNull(viewModel(handle.roundTrip()).detail.value)
    }

    @Test
    fun `a detail opened after closing does not reuse an earlier opening's ViewModels`() {
        // Reusing "folder:abc#1" would bring back the edits discarded when it closed.
        val handle = SavedStateHandle()
        val viewModel = viewModel(handle)
        viewModel.openDetail(DetailSelection.FOLDER + "abc")
        viewModel.closeDetail()

        val restored = viewModel(handle.roundTrip())
        restored.openDetail(DetailSelection.FOLDER + "abc")

        assertEquals("folder:abc#2", restored.detail.value?.viewModelKey)
    }
}
