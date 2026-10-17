package com.nutomic.syncthingandroid.settings

import dagger.hilt.android.AndroidEntryPoint
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.lifecycle.lifecycleScope
import com.nutomic.syncthingandroid.activities.SyncthingActivity
import com.nutomic.syncthingandroid.service.NotificationHandler
import com.nutomic.syncthingandroid.service.SyncthingConnection
import com.nutomic.syncthingandroid.theme.ApplicationTheme
import jakarta.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import me.zhanghai.compose.preference.Preferences
import me.zhanghai.compose.preference.ProvidePreferenceLocals

@AndroidEntryPoint
class SettingsActivity : SyncthingActivity() {

    @Inject
    lateinit var sharedPreferences: SharedPreferences
    lateinit var prefFlow: MutableStateFlow<Preferences>
    @Inject
    lateinit var notificationHandler: NotificationHandler
    @Inject
    lateinit var connection: SyncthingConnection

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefFlow = createPreferenceFlow(sharedPreferences, lifecycleScope)

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
                    )
                }
            }
        }
    }

    override fun onStop() {
        // The screens keep the connection bound; it outlives them by a few seconds.
        connection.snapshot.value.service?.let {
            notificationHandler.updatePersistentNotification(it)
        }
        super.onStop()
    }

    companion object {
        const val EXTRA_START_DESTINATION = "com.nutomic.syncthingandroid.settings.EXTRA_START_DESTINATION"
    }
}
