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
import com.nutomic.syncthingandroid.service.SyncthingServiceBinder
import com.nutomic.syncthingandroid.theme.ApplicationTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback

/** Shows a folder's details and changes them, or adds a new folder ([FolderEditController]). */
@AndroidEntryPoint
class FolderActivity : SyncthingActivity() {

    private val viewModel: FolderEditViewModel by
        viewModels(
            extrasProducer = {
                defaultViewModelCreationExtras.withCreationCallback<FolderEditViewModel.Factory> {
                    it.create(FolderEditArgs.of(intent))
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
                    FolderRequest.entries,
                    EditCallbacks(
                        onResult = viewModel::onResult,
                        onClose = { saved ->
                            setResult(if (saved) Activity.RESULT_OK else Activity.RESULT_CANCELED)
                            finish()
                        },
                        onLeave = ::finish,
                    ),
                )
                controller.form?.let {
                    FolderEditScreen(
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
        (iBinder as SyncthingServiceBinder)
            .service
            .notificationHandler
            .cancelConsentNotification(intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))
    }

    override fun onDestroy() {
        super.onDestroy()
        service
            ?.notificationHandler
            ?.cancelConsentNotification(intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))
    }

    companion object {
        const val EXTRA_DEVICE_ID = ".activities.FolderActivity.DEVICE_ID"
        const val EXTRA_FOLDER_ID = ".activities.FolderActivity.FOLDER_ID"
        const val EXTRA_FOLDER_LABEL = ".activities.FolderActivity.FOLDER_LABEL"
        const val EXTRA_IS_CREATE = ".activities.FolderActivity.IS_CREATE"
        const val EXTRA_NOTIFICATION_ID = ".activities.FolderActivity.NOTIFICATION_ID"
        const val EXTRA_RECEIVE_ENCRYPTED = ".activities.FolderActivity.RECEIVE_ENCRYPTED"
        const val EXTRA_REMOTE_ENCRYPTED = ".activities.FolderActivity.REMOTE_ENCRYPTED"

        const val FOLDER_ADD_CODE = 402

        @JvmStatic
        fun createIntent(context: Context): Intent =
            Intent(context, FolderActivity::class.java).putExtra(EXTRA_IS_CREATE, true)
    }
}
