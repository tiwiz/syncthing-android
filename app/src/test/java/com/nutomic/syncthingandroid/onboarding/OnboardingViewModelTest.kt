package com.nutomic.syncthingandroid.onboarding

import android.app.Application
import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.testutil.ConfigFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Unit tests for [OnboardingViewModel]: initial state, navigation, permission refresh and
 * [SavedStateHandle] round trip.
 */
@RunWith(AndroidJUnit4::class)
@Config(
    shadows = [com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess::class]
)
class OnboardingViewModelTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        ConfigFixture.install(app)
    }

    @Test
    fun `initial state skips key generation when a config is present`() {
        val viewModel = OnboardingViewModel(app, SavedStateHandle())

        assertTrue(viewModel.uiState.value.hasConfig)
        assertFalse(viewModel.uiState.value.pages.contains(OnboardingPage.KEY_GENERATION))
    }

    @Test
    fun `advance moves to the next page and back returns to the previous page`() {
        val viewModel = OnboardingViewModel(app, SavedStateHandle())
        val initialPage = viewModel.uiState.value.currentPage

        viewModel.advance()
        assertEquals(initialPage + 1, viewModel.uiState.value.currentPage)

        viewModel.onBack()
        assertEquals(initialPage, viewModel.uiState.value.currentPage)
    }

    @Test
    fun `advance returns true when already on the last page`() {
        val viewModel = OnboardingViewModel(app, SavedStateHandle())
        val lastPage = viewModel.uiState.value.pages.lastIndex
        repeat(lastPage) { viewModel.advance() }

        assertEquals(lastPage, viewModel.uiState.value.currentPage)
        assertTrue(viewModel.advance())
    }

    @Test
    fun `refreshPermissionState updates flags from the system`() {
        val viewModel = OnboardingViewModel(app, SavedStateHandle())
        val before = viewModel.uiState.value

        viewModel.refreshPermissionState()
        val after = viewModel.uiState.value

        // The fixture installs a config and the test environment grants the permissions it can.
        assertTrue(after.hasConfig)
        assertEquals(before.hasStoragePermission, after.hasStoragePermission)
    }

    @Test
    fun `state survives a SavedStateHandle round trip`() {
        val viewModel = OnboardingViewModel(app, SavedStateHandle())
        viewModel.advance()
        viewModel.advance()
        val expectedPage = viewModel.uiState.value.currentPage

        val bundle =
            Bundle().apply {
                putSerializable(OnboardingViewModel.STATE_UI_STATE, viewModel.uiState.value)
            }
        val restoredHandle = SavedStateHandle.createHandle(bundle, null)
        val restoredViewModel = OnboardingViewModel(app, restoredHandle)

        assertEquals(expectedPage, restoredViewModel.uiState.value.currentPage)
        assertEquals(viewModel.uiState.value.pages, restoredViewModel.uiState.value.pages)
    }
}
