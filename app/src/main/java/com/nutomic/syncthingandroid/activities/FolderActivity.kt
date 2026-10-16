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
import androidx.lifecycle.lifecycleScope
import com.nutomic.syncthingandroid.SyncthingApp
import com.nutomic.syncthingandroid.service.RestApi
import com.nutomic.syncthingandroid.service.SyncthingServiceBinder
import com.nutomic.syncthingandroid.theme.ApplicationTheme
import com.nutomic.syncthingandroid.util.ConfigRouter
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

/** Shows a folder's details and changes them, or adds a new folder ([FolderEditController]). */
class FolderActivity : SyncthingActivity(), FolderEditHost {

    @Inject lateinit var preferences: SharedPreferences

    private var controller: FolderEditController? = null

    private val backPressedCallback =
        object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                controller?.actions?.onBack()
            }
        }

    private val launchers =
        FolderRequest.entries.associateWith { request ->
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                controller?.onResult(request, it)
            }
        }

    override val restApi: RestApi?
        get() = api

    override val scope: CoroutineScope
        get() = lifecycleScope

    override fun launch(request: FolderRequest, intent: Intent) =
        checkNotNull(launchers[request]).launch(intent)

    override fun open(intent: Intent) = startActivity(intent)

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
            FolderEditController.create(
                this,
                preferences,
                ConfigRouter(this),
                this,
                FolderEditArgs.of(intent),
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
                    FolderEditScreen(
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        controller?.saveState(outState)
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
