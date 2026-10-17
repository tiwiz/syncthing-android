package com.nutomic.syncthingandroid.activities

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.nutomic.syncthingandroid.fragments.DeviceIdDialogFragment
import com.nutomic.syncthingandroid.service.SyncthingServiceBinder
import com.nutomic.syncthingandroid.theme.ApplicationTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback

/** Shows a device's details and changes them, or adds a new device ([DeviceEditController]). */
@AndroidEntryPoint
class DeviceActivity : SyncthingActivity() {

    private val viewModel: DeviceEditViewModel by
        viewModels(
            extrasProducer = {
                defaultViewModelCreationExtras.withCreationCallback<DeviceEditViewModel.Factory> {
                    it.create(DeviceEditArgs.of(intent))
                }
            }
        )

    private val backPressedCallback =
        object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                viewModel.controller?.actions?.onBack()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val controller = viewModel.controller
        if (controller == null) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        setContent {
            ApplicationTheme {
                EditEffects(
                    viewModel.events,
                    DeviceRequest.entries,
                    EditCallbacks(
                        onResult = viewModel::onResult,
                        onClose = { saved ->
                            setResult(if (saved) Activity.RESULT_OK else Activity.RESULT_CANCELED)
                            finish()
                        },
                        onLeave = ::finish,
                        onShowDeviceId = { name, id ->
                            DeviceIdDialogFragment.show(
                                supportFragmentManager,
                                name,
                                id,
                                false,
                            )
                        },
                    ),
                    viewModel::refreshWhileShown,
                )
                controller.form?.let {
                    DeviceEditScreen(
                        form = it,
                        info = controller.info,
                        dialog = controller.dialog,
                        actions = controller.actions,
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
    }

    override fun onDestroy() {
        super.onDestroy()
        service
            ?.notificationHandler
            ?.cancelConsentNotification(intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))
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
