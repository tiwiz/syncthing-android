package com.nutomic.syncthingandroid.activities

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.IBinder
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.nutomic.syncthingandroid.SyncthingApp
import com.nutomic.syncthingandroid.fragments.DeviceIdDialogFragment
import com.nutomic.syncthingandroid.service.RestApi
import com.nutomic.syncthingandroid.service.SyncthingServiceBinder
import com.nutomic.syncthingandroid.theme.ApplicationTheme
import com.nutomic.syncthingandroid.util.ConfigRouter
import javax.inject.Inject

/** Shows a device's details and changes them, or adds a new device ([DeviceEditController]). */
class DeviceActivity : SyncthingActivity(), DeviceEditHost {

    @Inject lateinit var preferences: SharedPreferences

    private var controller: DeviceEditController? = null

    private val backPressedCallback =
        object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                controller?.actions?.onBack()
            }
        }

    private val launchers =
        DeviceRequest.entries.associateWith { request ->
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                controller?.onResult(request, it)
            }
        }

    override val restApi: RestApi?
        get() = api

    override fun launch(request: DeviceRequest, intent: Intent) =
        checkNotNull(launchers[request]).launch(intent)

    override fun open(intent: Intent) = startActivity(intent)

    override fun showDeviceId(name: String, deviceId: String) =
        DeviceIdDialogFragment.show(supportFragmentManager, name, deviceId, false)

    override fun close(saved: Boolean) {
        setResult(if (saved) Activity.RESULT_OK else Activity.RESULT_CANCELED)
        finish()
    }

    override fun leave() {
        backPressedCallback.isEnabled = false
        onBackPressedDispatcher.onBackPressed()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as SyncthingApp).component().inject(this)
        val created =
            DeviceEditController.create(
                this,
                preferences,
                ConfigRouter(this),
                this,
                DeviceEditArgs.of(intent),
                savedInstanceState,
            )
        if (created == null) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        controller = created
        setContent {
            ApplicationTheme {
                created.form?.let {
                    DeviceEditScreen(
                        form = it,
                        info = created.info,
                        dialog = created.dialog,
                        actions = created.actions,
                    )
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, backPressedCallback)
    }

    override fun onServiceConnected(componentName: ComponentName, iBinder: IBinder) {
        super.onServiceConnected(componentName, iBinder)
        val service = (iBinder as SyncthingServiceBinder).service
        service.notificationHandler.cancelConsentNotification(
            intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        )
        service.api?.let { controller?.onApiAvailable(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        service
            ?.notificationHandler
            ?.cancelConsentNotification(intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        controller?.saveState(outState)
    }

    companion object {
        const val EXTRA_NOTIFICATION_ID = ".activities.DeviceActivity.NOTIFICATION_ID"
        const val EXTRA_DEVICE_ID = ".activities.DeviceActivity.DEVICE_ID"
        const val EXTRA_DEVICE_NAME = ".activities.DeviceActivity.DEVICE_NAME"
        const val EXTRA_IS_CREATE = ".activities.DeviceActivity.IS_CREATE"

        const val DEVICE_ADD_CODE = 401

        @JvmStatic
        fun createIntent(context: Context): Intent =
            Intent(context, DeviceActivity::class.java).putExtra(EXTRA_IS_CREATE, true)
    }
}
