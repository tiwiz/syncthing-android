package com.nutomic.syncthingandroid.settings

import android.content.ComponentName
import android.content.SharedPreferences
import android.os.Bundle
import android.os.IBinder
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.nutomic.syncthingandroid.SyncthingApp
import com.nutomic.syncthingandroid.activities.SyncthingActivity
import com.nutomic.syncthingandroid.service.NotificationHandler
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.theme.ApplicationTheme
import jakarta.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import me.zhanghai.compose.preference.Preferences
import me.zhanghai.compose.preference.ProvidePreferenceLocals

/**
 * The service SettingsActivity is bound to, if any, and a counter that changes whenever the
 * service's state does. Both are snapshot state, so screens that read them recompose on changes.
 */
@Stable
class SyncthingServiceState {
    var service by mutableStateOf<SyncthingService?>(null)
        internal set

    var updateTick by mutableIntStateOf(0)
        internal set
}

class SettingsActivity : SyncthingActivity() {

    @Inject
    lateinit var sharedPreferences: SharedPreferences
    lateinit var prefFlow: MutableStateFlow<Preferences>
    @Inject
    lateinit var notificationHandler: NotificationHandler

    private val serviceState = SyncthingServiceState()

    // The tick updates the UI whenever the state of the Syncthing service changes.
    val stateChangeListener = SyncthingService.OnServiceStateChangeListener {
        serviceState.updateTick++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        (application as SyncthingApp).component().inject(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val activityScope = this.lifecycleScope
        prefFlow = createPreferenceFlow(sharedPreferences, activityScope)

        val routeStr = intent.getStringExtra(EXTRA_START_DESTINATION)
        val startDestination: SettingsRoute = SettingsRoute.fromString(routeStr)

        setContent {
            val backStack = rememberSettingsNavBackStack(startDestination)
            val navigator = remember(backStack) {
                object : Navigator<SettingsRoute> {
                    override fun navigateTo(route: SettingsRoute) {
                        backStack.add(route)
                    }
                    override fun navigateBack() {
                        if (backStack.size == 1) {
                            finish()
                        } else {
                            backStack.removeLastOrNull()
                        }
                    }
                    override fun navigateUp() {
                        finish()
                    }
                }
            }

            ApplicationTheme {
                ProvidePreferenceLocals(flow = prefFlow) {
                    SettingsNavDisplay(
                        backStack = backStack,
                        navigator = navigator,
                        serviceState = serviceState,
                        activityScope = activityScope,
                    )
                }
            }
        }
    }

    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        super.onServiceConnected(name, service)
        serviceState.service = this.service
        serviceState.service?.registerOnServiceStateChangeListener(stateChangeListener)
        serviceState.updateTick++
    }

    override fun onServiceDisconnected(name: ComponentName) {
        serviceState.service?.unregisterOnServiceStateChangeListener(stateChangeListener)
        super.onServiceDisconnected(name)
        serviceState.service = null
        serviceState.updateTick++
    }

    override fun onStop() {
        serviceState.service?.let {
            notificationHandler.updatePersistentNotification(it)
        }
        super.onStop()
    }

    override fun onDestroy() {
        serviceState.service?.unregisterOnServiceStateChangeListener(stateChangeListener)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_START_DESTINATION = "com.nutomic.syncthingandroid.settings.EXTRA_START_DESTINATION"
    }
}
