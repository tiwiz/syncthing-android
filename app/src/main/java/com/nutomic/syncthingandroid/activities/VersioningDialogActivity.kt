package com.nutomic.syncthingandroid.activities

import android.content.Intent
import android.os.Bundle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nutomic.syncthingandroid.theme.ApplicationTheme

/**
 * Picks the file versioning type of a folder and its parameters. The intent extras are the current
 * parameters plus "type"; the result has the same form, on "Finish" and on back alike.
 */
class VersioningDialogActivity : ThemedAppCompatActivity() {

    private val viewModel: VersioningViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val params by viewModel.params.collectAsStateWithLifecycle()
            ApplicationTheme {
                VersioningScreen(
                    params = params,
                    onTypeChange = viewModel::changeType,
                    onParamChange = viewModel::changeParam,
                    onFinish = ::finishWithResult,
                )
            }
        }
        onBackPressedDispatcher.addCallback(this) { finishWithResult() }
    }

    private fun finishWithResult() {
        setResult(RESULT_OK, Intent().putExtras(viewModel.params.value.toBundle()))
        finish()
    }

    private fun Map<String, String>.toBundle() =
        Bundle().apply { forEach { (key, value) -> putString(key, value) } }
}
