package com.nutomic.syncthingandroid.activities

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The web page's state: its URL, whether it is still loading, and whether the user already accepted
 * an invalid certificate, which survives a recreation.
 */
internal class WebViewViewModel(private val savedState: SavedStateHandle) : ViewModel() {

    /** The page, from the intent; null if the intent has none. */
    val url: String? = savedState[WebViewActivity.EXTRA_WEB_URL]

    private val loadingState = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = loadingState.asStateFlow()

    /** Once the user accepted an invalid certificate, later errors on the page do not ask again. */
    var certificateAccepted: Boolean
        get() = savedState[CERTIFICATE_ACCEPTED] ?: false
        set(value) {
            savedState[CERTIFICATE_ACCEPTED] = value
        }

    /** A new WebView loads the page from scratch. */
    fun onLoadStarted() {
        loadingState.value = true
    }

    fun onPageFinished() {
        loadingState.value = false
    }

    private companion object {
        const val CERTIFICATE_ACCEPTED = "certificateAccepted"
    }
}
