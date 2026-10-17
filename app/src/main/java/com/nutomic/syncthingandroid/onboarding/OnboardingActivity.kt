package com.nutomic.syncthingandroid.onboarding

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.activities.MainActivity
import com.nutomic.syncthingandroid.activities.ThemedAppCompatActivity
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.theme.ApplicationTheme
import com.nutomic.syncthingandroid.util.isRunningOnTV
import com.nutomic.syncthingandroid.webgui.WebGuiActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class OnboardingActivity : ThemedAppCompatActivity() {

    companion object {
        private const val TAG = "OnboardingActivity"
    }

    @Inject
    lateinit var prefs: SharedPreferences

    internal val viewModel: OnboardingViewModel by viewModels()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        viewModel.refreshPermissionState()
        if (isGranted) {
            Toast.makeText(this, R.string.permission_granted, Toast.LENGTH_SHORT).show()
            Log.i(TAG, "User granted POST_NOTIFICATIONS permission.")
            viewModel.advanceIfCurrentPage(OnboardingPage.NOTIFICATION_PERMISSION)
        } else {
            Log.i(TAG, "User denied POST_NOTIFICATIONS permission.")
        }
    }

    private val localNetworkPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        viewModel.refreshPermissionState()
        if (isGranted) {
            Toast.makeText(this, R.string.permission_granted, Toast.LENGTH_SHORT).show()
            Log.i(TAG, "User granted ACCESS_LOCAL_NETWORK permission.")
            viewModel.advanceIfCurrentPage(OnboardingPage.LOCAL_NETWORK_PERMISSION)
        } else {
            Log.i(TAG, "User denied ACCESS_LOCAL_NETWORK permission.")
        }
    }

    private val fineLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        viewModel.refreshPermissionState()
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
        viewModel.refreshPermissionState()
        if (isGranted) {
            Toast.makeText(this, R.string.permission_granted, Toast.LENGTH_SHORT).show()
            Log.i(TAG, "User granted ACCESS_BACKGROUND_LOCATION permission.")
            viewModel.advanceIfCurrentPage(OnboardingPage.LOCATION_PERMISSION)
        } else {
            Log.i(TAG, "User denied ACCESS_BACKGROUND_LOCATION permission.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val isRunningOnTv = isRunningOnTV(this)
        Log.d(TAG, if (isRunningOnTv) "Running on a TV Device" else "Running on a non-TV Device")

        if (viewModel.shouldSkipToMain()) {
            return startApp()
        }

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
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

    override fun onResume() {
        super.onResume()
        val oldState = viewModel.uiState.value
        viewModel.refreshPermissionState()
        val newState = viewModel.uiState.value
        val currentPage = newState.pages.getOrNull(newState.currentPage)
        if (currentPage == OnboardingPage.STORAGE_PERMISSION &&
            !oldState.hasStoragePermission &&
            newState.hasStoragePermission
        ) {
            advance()
        } else if (currentPage == OnboardingPage.BATTERY_OPTIMIZATION &&
            !oldState.hasIgnoreDozePermission &&
            newState.hasIgnoreDozePermission
        ) {
            advance()
        } else if (currentPage == OnboardingPage.NOTIFICATION_PERMISSION &&
            !oldState.hasNotificationPermission &&
            newState.hasNotificationPermission
        ) {
            advance()
        } else if (currentPage == OnboardingPage.LOCAL_NETWORK_PERMISSION &&
            !oldState.hasLocalNetworkPermission &&
            newState.hasLocalNetworkPermission
        ) {
            advance()
        }
    }

    private fun startApp() {
        val startIntoWebGui = prefs.getBoolean(Constants.PREF_START_INTO_WEB_GUI, false)
        val mainIntent = Intent(this, MainActivity::class.java)

        /*
         * In case start_into_web_gui option is enabled, start both activities
         * so that back navigation works as expected.
         */
        if (startIntoWebGui) {
            startActivities(
                arrayOf(
                    mainIntent,
                    Intent(this, WebGuiActivity::class.java),
                ),
            )
        } else {
            startActivity(mainIntent)
        }
        finish()
    }

    private fun handleBack() {
        val state = viewModel.uiState.value
        if (state.keyGenerationRunning) {
            return
        }
        if (state.currentPage == 0) {
            finish()
            return
        }
        viewModel.onBack()
    }

    private fun advance() {
        if (viewModel.advance()) {
            startApp()
        }
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
}
