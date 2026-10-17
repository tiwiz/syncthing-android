package com.nutomic.syncthingandroid.onboarding

import java.io.Serializable

/**
 * State of the onboarding flow, kept in [OnboardingViewModel] and persisted across configuration
 * changes through [androidx.lifecycle.SavedStateHandle].
 */
data class OnboardingUiState(
    val pages: List<OnboardingPage> = emptyList(),
    val currentPage: Int = 0,
    val hasStoragePermission: Boolean = false,
    val hasIgnoreDozePermission: Boolean = false,
    val hasLocationPermission: Boolean = false,
    val hasLocalNetworkPermission: Boolean = false,
    val hasNotificationPermission: Boolean = false,
    val hasConfig: Boolean = false,
    val isRunningOnTv: Boolean = false,
    val keyGenerationRunning: Boolean = false,
    val keyGenerationFailed: Boolean = false,
    val keyGenerationStatus: String = "",
) : Serializable {
    private companion object {
        private const val serialVersionUID = 1L
    }
}
