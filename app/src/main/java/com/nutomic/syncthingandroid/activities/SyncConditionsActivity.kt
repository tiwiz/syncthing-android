package com.nutomic.syncthingandroid.activities

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nutomic.syncthingandroid.SyncthingApp
import com.nutomic.syncthingandroid.theme.ApplicationTheme
import javax.inject.Inject
import me.zhanghai.compose.preference.ProvidePreferenceTheme

/**
 * The run conditions of one folder or device. They are saved whenever the screen pauses, also
 * unchanged: conditions turned off globally since then must be saved off.
 */
class SyncConditionsActivity : SyncthingActivity() {

    @Inject lateinit var preferences: SharedPreferences

    private lateinit var prefixAndId: String
    private var conditions: ObjectRunConditions? by mutableStateOf(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as SyncthingApp).component().inject(this)
        if (
            !intent.hasExtra(EXTRA_OBJECT_PREFIX_AND_ID) ||
                !intent.hasExtra(EXTRA_OBJECT_READABLE_NAME)
        ) {
            Log.e(TAG, "onCreate extra missing")
            return
        }
        prefixAndId = checkNotNull(intent.getStringExtra(EXTRA_OBJECT_PREFIX_AND_ID))
        Log.v(
            TAG,
            "Prefix is '$prefixAndId' (${intent.getStringExtra(EXTRA_OBJECT_READABLE_NAME)})",
        )
        val global = GlobalRunConditions.read(preferences)
        conditions = ObjectRunConditions.read(preferences, prefixAndId, global)
        setContent {
            ApplicationTheme {
                ProvidePreferenceTheme {
                    conditions?.let { current ->
                        SyncConditionsScreen(
                            global = global,
                            conditions = current,
                            onConditionsChange = { conditions = it },
                            onNavigateBack = ::finishWithResult,
                        )
                    }
                }
            }
        }
        onBackPressedDispatcher.addCallback(this) { finishWithResult() }
    }

    override fun onPause() {
        super.onPause()
        conditions?.let {
            Log.v(TAG, "onPause: Saving prefs ...")
            it.save(preferences, prefixAndId)
        }
    }

    private fun finishWithResult() {
        setResult(Activity.RESULT_OK)
        finish()
    }

    companion object {
        private const val TAG = "SyncConditionsActivity"

        private const val EXTRA_OBJECT_PREFIX_AND_ID =
            ".activities.SyncConditionsActivity.OBJECT_PREFIX_AND_ID"

        private const val EXTRA_OBJECT_READABLE_NAME =
            ".activities.SyncConditionsActivity.OBJECT_READABLE_NAME"

        @JvmStatic
        fun createIntent(
            context: Context,
            objectPrefixAndId: String,
            objectReadableName: String,
        ): Intent =
            Intent(context, SyncConditionsActivity::class.java)
                .putExtra(EXTRA_OBJECT_PREFIX_AND_ID, objectPrefixAndId)
                .putExtra(EXTRA_OBJECT_READABLE_NAME, objectReadableName)
    }
}
