package com.nutomic.syncthingandroid.onboarding

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.util.isRunningOnTV
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The onboarding screen's state: which pages are shown, which one is current, and the progress of
 * key generation. Survives configuration changes through [SavedStateHandle].
 */
@HiltViewModel
internal class OnboardingViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val state = MutableStateFlow(initializeState())
    val uiState: StateFlow<OnboardingUiState> = state.asStateFlow()

    init {
        // Keep the handle in sync so the whole UI state survives process death and recreation.
        viewModelScope.launch { state.collect { savedStateHandle[STATE_UI_STATE] = it } }
        // If we landed on the key generation page without a finished config (e.g. its coroutine was
        // interrupted by recreation), (re)start generation as moveToPage would.
        if (
            state.value.pages.getOrNull(state.value.currentPage) == OnboardingPage.KEY_GENERATION &&
                !state.value.keyGenerationFailed &&
                !state.value.hasConfig
        ) {
            startKeyGeneration()
        }
    }

    private fun initializeState(): OnboardingUiState {
        val restored = savedStateHandle.get<OnboardingUiState>(STATE_UI_STATE)
        if (restored != null) {
            // Permissions/config may have changed while we were gone; re-derive these.
            // A key-generation coroutine cannot survive recreation, so never restore it as running.
            return restored.copy(
                hasStoragePermission = context.haveStoragePermission(),
                hasIgnoreDozePermission = context.haveIgnoreDozePermission(),
                hasLocationPermission = context.haveLocationPermission(),
                hasLocalNetworkPermission = context.haveLocalNetworkPermission(),
                hasNotificationPermission = context.haveNotificationPermission(),
                hasConfig = context.checkForParseableConfig(),
                keyGenerationRunning = false,
            )
        }

        val haveStoragePermission = context.haveStoragePermission()
        val haveIgnoreDozePermission = context.haveIgnoreDozePermission()
        val haveLocationPermission = context.haveLocationPermission()
        val haveLocalNetworkPermission = context.haveLocalNetworkPermission()
        val haveNotificationPermission = context.haveNotificationPermission()
        val haveConfig = context.checkForParseableConfig()
        val isRunningOnTv = isRunningOnTV(context)

        return OnboardingUiState(
            pages =
                listOfNotNull(
                    OnboardingPage.WELCOME,
                    OnboardingPage.STORAGE_PERMISSION.takeUnless { haveStoragePermission },
                    OnboardingPage.BATTERY_OPTIMIZATION.takeUnless { haveIgnoreDozePermission },
                    OnboardingPage.LOCATION_PERMISSION.takeUnless { haveLocationPermission },
                    OnboardingPage.LOCAL_NETWORK_PERMISSION.takeUnless {
                        haveLocalNetworkPermission
                    },
                    OnboardingPage.NOTIFICATION_PERMISSION.takeUnless {
                        haveNotificationPermission
                    },
                    OnboardingPage.KEY_GENERATION.takeUnless { haveConfig },
                ),
            hasStoragePermission = haveStoragePermission,
            hasIgnoreDozePermission = haveIgnoreDozePermission,
            hasLocationPermission = haveLocationPermission,
            hasLocalNetworkPermission = haveLocalNetworkPermission,
            hasNotificationPermission = haveNotificationPermission,
            hasConfig = haveConfig,
            isRunningOnTv = isRunningOnTv,
            keyGenerationStatus = context.getString(R.string.web_gui_creating_key),
        )
    }

    /** Whether the minimum requirements are met and the app can be started. */
    fun shouldSkipToMain(): Boolean =
        state.value.hasStoragePermission &&
            state.value.hasLocalNetworkPermission &&
            state.value.hasNotificationPermission &&
            state.value.hasConfig

    /** Refreshes the permission/config flags from the system. */
    fun refreshPermissionState() {
        state.update {
            it.copy(
                hasStoragePermission = context.haveStoragePermission(),
                hasIgnoreDozePermission = context.haveIgnoreDozePermission(),
                hasLocationPermission = context.haveLocationPermission(),
                hasLocalNetworkPermission = context.haveLocalNetworkPermission(),
                hasNotificationPermission = context.haveNotificationPermission(),
                hasConfig = context.checkForParseableConfig(),
            )
        }
    }

    /** Goes back one page, or finishes if already on the first page. */
    fun onBack() {
        if (state.value.keyGenerationRunning) return
        if (state.value.currentPage == 0) return
        moveToPage(state.value.currentPage - 1)
    }

    /** Advances to the next page, or signals the activity to start the app if on the last page. */
    fun advance(): Boolean {
        val nextPage = state.value.currentPage + 1
        return if (nextPage < state.value.pages.size) {
            moveToPage(nextPage)
            false
        } else {
            true
        }
    }

    /** If [page] is the current one, advances past it (used after a permission is granted). */
    fun advanceIfCurrentPage(page: OnboardingPage) {
        if (state.value.pages.getOrNull(state.value.currentPage) == page) {
            advance()
        }
    }

    private fun moveToPage(page: Int) {
        val boundedPage = page.coerceIn(0, state.value.pages.lastIndex)
        state.update { it.copy(currentPage = boundedPage) }
        if (state.value.pages.getOrNull(boundedPage) == OnboardingPage.KEY_GENERATION) {
            startKeyGeneration()
        }
    }

    /** Starts key generation if it is not already running and there is no config yet. */
    fun startKeyGeneration() {
        if (state.value.keyGenerationRunning || state.value.hasConfig) return
        state.update {
            it.copy(
                keyGenerationRunning = true,
                keyGenerationFailed = false,
                keyGenerationStatus = context.getString(R.string.web_gui_creating_key),
            )
        }
        viewModelScope.launch {
            val errorMessage = context.generateConfig()
            if (errorMessage != null) {
                state.update {
                    it.copy(
                        keyGenerationRunning = false,
                        keyGenerationFailed = true,
                        keyGenerationStatus = errorMessage,
                    )
                }
                return@launch
            }

            if (!context.checkForParseableConfig()) {
                state.update {
                    it.copy(
                        keyGenerationRunning = false,
                        keyGenerationFailed = true,
                        hasConfig = false,
                        keyGenerationStatus = context.getString(R.string.config_read_failed),
                    )
                }
                return@launch
            }

            state.update {
                it.copy(
                    keyGenerationRunning = false,
                    keyGenerationFailed = false,
                    hasConfig = true,
                    keyGenerationStatus = context.getString(R.string.key_generation_success),
                )
            }
        }
    }

    internal companion object {
        // Key used to persist the whole UI state across configuration changes (e.g. rotation).
        internal const val STATE_UI_STATE = "onboarding_ui_state"
    }
}
