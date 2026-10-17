package com.nutomic.syncthingandroid.activities

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nutomic.syncthingandroid.theme.ApplicationTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * The run conditions of one folder or device. They are saved whenever the screen pauses, also
 * unchanged: conditions turned off globally since then must be saved off.
 */
@AndroidEntryPoint
class SyncConditionsActivity : SyncthingActivity() {

    private val viewModel: SyncConditionsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (
            !intent.hasExtra(EXTRA_OBJECT_PREFIX_AND_ID) ||
                !intent.hasExtra(EXTRA_OBJECT_READABLE_NAME)
        ) {
            Log.e(TAG, "onCreate extra missing")
            return
        }
        Log.v(
            TAG,
            "Prefix is '${intent.getStringExtra(EXTRA_OBJECT_PREFIX_AND_ID)}' " +
                "(${intent.getStringExtra(EXTRA_OBJECT_READABLE_NAME)})",
        )
        setContent {
            val conditions by viewModel.conditions.collectAsStateWithLifecycle()
            ApplicationTheme {
                conditions?.let { current ->
                    SyncConditionsScreen(
                        global = viewModel.global,
                        conditions = current,
                        onConditionsChange = viewModel::change,
                        onNavigateBack = ::finishWithResult,
                    )
                }
            }
        }
        onBackPressedDispatcher.addCallback(this) { finishWithResult() }
    }

    override fun onPause() {
        super.onPause()
        Log.v(TAG, "onPause: Saving prefs ...")
        viewModel.save()
    }

    private fun finishWithResult() {
        setResult(Activity.RESULT_OK)
        finish()
    }

    companion object {
        private const val TAG = "SyncConditionsActivity"

        internal const val EXTRA_OBJECT_PREFIX_AND_ID =
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
