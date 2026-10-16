package com.nutomic.syncthingandroid.onboarding

import dagger.hilt.android.AndroidEntryPoint
import com.nutomic.syncthingandroid.util.isRunningOnTV
import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.os.BundleCompat
import java.io.Serializable
import androidx.lifecycle.lifecycleScope
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.activities.MainActivity
import com.nutomic.syncthingandroid.activities.ThemedAppCompatActivity
import com.nutomic.syncthingandroid.webgui.WebGuiActivity
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.theme.ApplicationTheme
import javax.inject.Inject
import kotlinx.coroutines.launch

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

@AndroidEntryPoint
class OnboardingActivity : ThemedAppCompatActivity() {

    companion object {
        private const val TAG = "OnboardingActivity"

        // Key used to persist the whole UI state across configuration changes (e.g. rotation).
        private const val STATE_UI_STATE = "onboarding_ui_state"
    }

    @Inject
    lateinit var prefs: SharedPreferences

    private var uiState by mutableStateOf(OnboardingUiState())

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        refreshPermissionState()
        if (isGranted) {
            Toast.makeText(this, R.string.permission_granted, Toast.LENGTH_SHORT).show()
            Log.i(TAG, "User granted POST_NOTIFICATIONS permission.")
            advanceIfCurrentPage(OnboardingPage.NOTIFICATION_PERMISSION)
        } else {
            Log.i(TAG, "User denied POST_NOTIFICATIONS permission.")
        }
    }

    private val localNetworkPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        refreshPermissionState()
        if (isGranted) {
            Toast.makeText(this, R.string.permission_granted, Toast.LENGTH_SHORT).show()
            Log.i(TAG, "User granted ACCESS_LOCAL_NETWORK permission.")
            advanceIfCurrentPage(OnboardingPage.LOCAL_NETWORK_PERMISSION)
        } else {
            Log.i(TAG, "User denied ACCESS_LOCAL_NETWORK permission.")
        }
    }

    private val fineLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        refreshPermissionState()
        if (!isGranted) {
            Log.i(TAG, "User denied ACCESS_FINE_LOCATION permission.")
            return@registerForActivityResult
        }
        Toast.makeText(this, R.string.permission_granted, Toast.LENGTH_SHORT).show()
        Log.i(TAG, "User granted ACCESS_FINE_LOCATION permission.")
        backgroundLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    private val backgroundLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        refreshPermissionState()
        if (isGranted) {
            Toast.makeText(this, R.string.permission_granted, Toast.LENGTH_SHORT).show()
            Log.i(TAG, "User granted ACCESS_BACKGROUND_LOCATION permission.")
            advanceIfCurrentPage(OnboardingPage.LOCATION_PERMISSION)
        } else {
            Log.i(TAG, "User denied ACCESS_BACKGROUND_LOCATION permission.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Not in ThemedAppCompatActivity: below Android 15 the View screens keep a coloured status
        // bar that needs light icons (EdgeToEdgeTest). Move it there once every activity uses Compose.

        val isRunningOnTv = isRunningOnTV(this)
        Log.d(TAG, if (isRunningOnTv) "Running on a TV Device" else "Running on a non-TV Device")

        val haveStoragePermission = haveStoragePermission()
        val haveIgnoreDozePermission = haveIgnoreDozePermission()
        val haveLocationPermission = haveLocationPermission()
        val haveLocalNetworkPermission = haveLocalNetworkPermission()
        val haveNotificationPermission = haveNotificationPermission()
        val haveConfig = checkForParseableConfig()

        // On recreation (e.g. rotation) restore the entire previous UI state so nothing resets,
        // including the page set, which is decided once and must stay stable for the whole flow.
        val savedState = restoreUiState(savedInstanceState)

        if (savedState == null) {
            val shouldSkipToMain = haveStoragePermission &&
                    haveLocalNetworkPermission &&
                    haveNotificationPermission &&
                    haveConfig
            if (shouldSkipToMain) {
                // minimum requirements met, go to main
                return startApp()
            }
        }

        uiState = savedState?.copy(
            // Permissions/config may have changed while we were gone; re-derive these.
            hasStoragePermission = haveStoragePermission,
            hasIgnoreDozePermission = haveIgnoreDozePermission,
            hasLocationPermission = haveLocationPermission,
            hasLocalNetworkPermission = haveLocalNetworkPermission,
            hasNotificationPermission = haveNotificationPermission,
            hasConfig = haveConfig,
            isRunningOnTv = isRunningOnTv,
            // A key-generation coroutine cannot survive recreation, so never restore it as running.
            keyGenerationRunning = false,
        ) ?: OnboardingUiState(
            pages = listOfNotNull(
                OnboardingPage.WELCOME,
                OnboardingPage.STORAGE_PERMISSION.takeUnless { haveStoragePermission },
                OnboardingPage.BATTERY_OPTIMIZATION.takeUnless { haveIgnoreDozePermission },
                OnboardingPage.LOCATION_PERMISSION.takeUnless { haveLocationPermission },
                OnboardingPage.LOCAL_NETWORK_PERMISSION.takeUnless { haveLocalNetworkPermission },
                OnboardingPage.NOTIFICATION_PERMISSION.takeUnless { haveNotificationPermission },
                OnboardingPage.KEY_GENERATION.takeUnless { haveConfig },
            ),
            hasStoragePermission = haveStoragePermission,
            hasIgnoreDozePermission = haveIgnoreDozePermission,
            hasLocationPermission = haveLocationPermission,
            hasLocalNetworkPermission = haveLocalNetworkPermission,
            hasNotificationPermission = haveNotificationPermission,
            hasConfig = haveConfig,
            isRunningOnTv = isRunningOnTv,
            keyGenerationStatus = getString(R.string.web_gui_creating_key),
        )

        // If we landed on the key generation page without a finished config (e.g. its coroutine was
        // interrupted by recreation), (re)start generation as moveToPage would.
        if (uiState.pages.getOrNull(uiState.currentPage) == OnboardingPage.KEY_GENERATION &&
            !uiState.keyGenerationFailed &&
            !uiState.hasConfig
        ) {
            startKeyGeneration()
        }

        setContent {
            ApplicationTheme {
                OnboardingScreen(
                    uiState = uiState,
                    onBack = ::handleBack,
                    onContinue = ::advance,
                    onFinishOnboarding = ::startApp,
                    onGrantLocationPermission = ::requestLocationPermission,
                    onGrantLocalNetworkPermission = ::requestLocalNetworkPermission,
                    onGrantNotificationPermission = ::requestNotificationPermission,
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Persist the whole UI state so it survives configuration changes.
        outState.putSerializable(STATE_UI_STATE, uiState)
    }

    /**
     * Restores the previously saved UI state, or null on the initial launch (no saved state yet).
     */
    private fun restoreUiState(savedInstanceState: Bundle?): OnboardingUiState? {
        savedInstanceState ?: return null
        return BundleCompat.getSerializable(
            savedInstanceState,
            STATE_UI_STATE,
            OnboardingUiState::class.java,
        )
    }

    override fun onResume() {
        super.onResume()
        val oldState = uiState
        refreshPermissionState()
        val currentPage = uiState.pages.getOrNull(uiState.currentPage)
        if (currentPage == OnboardingPage.STORAGE_PERMISSION &&
            !oldState.hasStoragePermission &&
            uiState.hasStoragePermission
        ) {
            advance()
        } else if (currentPage == OnboardingPage.BATTERY_OPTIMIZATION &&
            !oldState.hasIgnoreDozePermission &&
            uiState.hasIgnoreDozePermission
        ) {
            advance()
        } else if (currentPage == OnboardingPage.NOTIFICATION_PERMISSION &&
            !oldState.hasNotificationPermission &&
            uiState.hasNotificationPermission
        ) {
            advance()
        } else if (currentPage == OnboardingPage.LOCAL_NETWORK_PERMISSION &&
            !oldState.hasLocalNetworkPermission &&
            uiState.hasLocalNetworkPermission
        ) {
            advance()
        }
    }

    fun startApp() {
        val startIntoWebGui = prefs.getBoolean(Constants.PREF_START_INTO_WEB_GUI, false)
        val mainIntent = Intent(this, MainActivity::class.java)

        /*
         * In case start_into_web_gui option is enabled, start both activities
         * so that back navigation works as expected.
         */
        if (startIntoWebGui) {
            startActivities(arrayOf(
                mainIntent,
                Intent(this, WebGuiActivity::class.java)
            ))
        } else {
            startActivity(mainIntent)
        }
        finish()
    }

    private fun handleBack() {
        if (uiState.keyGenerationRunning) {
            return
        }
        if (uiState.currentPage == 0) {
            finish()
            return
        }
        moveToPage(uiState.currentPage - 1)
    }

    private fun advanceIfCurrentPage(page: OnboardingPage) {
        if (uiState.pages.getOrNull(uiState.currentPage) == page) {
            advance()
        }
    }

    private fun advance() {
        val nextPage = uiState.currentPage + 1
        if (nextPage < uiState.pages.size) {
            moveToPage(nextPage)
        } else {
            startApp()
        }
    }

    private fun moveToPage(page: Int) {
        val boundedPage = page.coerceIn(0, uiState.pages.lastIndex)
        uiState = uiState.copy(currentPage = boundedPage)
        if (uiState.pages.getOrNull(boundedPage) == OnboardingPage.KEY_GENERATION) {
            startKeyGeneration()
        }
    }

    private fun refreshPermissionState() {
        uiState = uiState.copy(
            hasStoragePermission = haveStoragePermission(),
            hasIgnoreDozePermission = haveIgnoreDozePermission(),
            hasLocationPermission = haveLocationPermission(),
            hasLocalNetworkPermission = haveLocalNetworkPermission(),
            hasNotificationPermission = haveNotificationPermission(),
            hasConfig = checkForParseableConfig(),
        )
    }

    private fun requestLocationPermission() {
        fineLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun requestLocalNetworkPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) {
            return
        }
        localNetworkPermissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
    }

    private fun startKeyGeneration() {
        if (uiState.keyGenerationRunning || uiState.hasConfig) {
            return
        }
        uiState = uiState.copy(
            keyGenerationRunning = true,
            keyGenerationFailed = false,
            keyGenerationStatus = getString(R.string.web_gui_creating_key),
        )
        lifecycleScope.launch {
            val errorMessage = generateConfig()

            if (errorMessage != null) {
                uiState = uiState.copy(
                    keyGenerationRunning = false,
                    keyGenerationFailed = true,
                    keyGenerationStatus = errorMessage,
                )
                return@launch
            }

            if (!checkForParseableConfig()) {
                uiState = uiState.copy(
                    keyGenerationRunning = false,
                    keyGenerationFailed = true,
                    hasConfig = false,
                    keyGenerationStatus = getString(R.string.config_read_failed),
                )
                return@launch
            }

            uiState = uiState.copy(
                keyGenerationRunning = false,
                keyGenerationFailed = false,
                hasConfig = true,
                keyGenerationStatus = getString(R.string.key_generation_success),
            )
        }
    }
}
