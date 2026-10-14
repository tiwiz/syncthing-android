package com.nutomic.syncthingandroid.activities

import android.content.Intent
import android.os.Bundle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateMapOf
import com.nutomic.syncthingandroid.theme.ApplicationTheme

/**
 * Picks the file versioning type of a folder and its parameters. The intent extras are the current
 * parameters plus "type"; the result has the same form, on "Finish" and on back alike.
 */
class VersioningDialogActivity : ThemedAppCompatActivity() {

    /**
     * All parameters seen so far, as strings; those of other types stay, as Syncthing ignores them.
     */
    private val params = mutableStateMapOf<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val arguments = savedInstanceState?.getBundle(STATE_PARAMS) ?: intent.extras ?: Bundle()
        for (key in arguments.keySet()) {
            arguments.getString(key)?.let { params[key] = it }
        }
        fillVersioningDefaults(params)
        setContent {
            ApplicationTheme {
                VersioningScreen(
                    params = params,
                    onTypeChange = { type ->
                        params[KEY_TYPE] = type
                        fillVersioningDefaults(params)
                    },
                    onParamChange = { key, value -> params[key] = value },
                    onFinish = ::finishWithResult,
                )
            }
        }
        onBackPressedDispatcher.addCallback(this) { finishWithResult() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBundle(STATE_PARAMS, params.toBundle())
    }

    private fun finishWithResult() {
        setResult(RESULT_OK, Intent().putExtras(params.toBundle()))
        finish()
    }

    private fun Map<String, String>.toBundle() =
        Bundle().apply { forEach { (key, value) -> putString(key, value) } }

    private companion object {
        const val STATE_PARAMS = "arguments"
    }
}
