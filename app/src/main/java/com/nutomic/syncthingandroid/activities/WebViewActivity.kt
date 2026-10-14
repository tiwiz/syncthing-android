package com.nutomic.syncthingandroid.activities

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.util.Log
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nutomic.syncthingandroid.theme.ApplicationTheme
import com.nutomic.syncthingandroid.util.isRunningOnTV

/** Shows a web page, such as the usage statistics, with a warning for invalid certificates. */
class WebViewActivity : SyncthingActivity() {

    private var isRunningOnTv = false
    private var webPageUrl = ""
    private var webView: WebView? = null

    private var loading by mutableStateOf(true)

    /** The certificate error waiting for the user's decision. */
    private var pendingSslError by mutableStateOf<SslErrorHandler?>(null)

    /** Once the user accepted an invalid certificate, later errors on the page do not ask again. */
    private var sslNoticeUserDecision = false

    private val webViewClient =
        object : WebViewClient() {
            @SuppressLint("WebViewClientOnReceivedSslError") // The user decides, see the dialog.
            override fun onReceivedSslError(
                view: WebView,
                handler: SslErrorHandler,
                error: SslError,
            ) {
                if (sslNoticeUserDecision) {
                    handler.proceed()
                } else {
                    pendingSslError = handler
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                loading = false
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isRunningOnTv = isRunningOnTV(this)
        val url = intent.getStringExtra(EXTRA_WEB_URL)
        if (url == null) {
            Log.e(TAG, "EXTRA_WEB_URL not set.")
            finish()
            return
        }
        webPageUrl = url
        enableEdgeToEdge()
        setContent {
            ApplicationTheme {
                WebViewScreen(
                    title = title.toString(),
                    url = webPageUrl,
                    loading = loading,
                    showSslNotice = pendingSslError != null,
                    canOpenInBrowser = !isRunningOnTv,
                    onSslNoticeResult = ::onSslNoticeResult,
                    onOpenInBrowser = ::openInBrowser,
                    onNavigateBack = ::finish,
                    webViewFactory = ::createWebView,
                )
            }
        }
        onBackPressedDispatcher.addCallback(this) {
            val current = webView
            if (current != null && current.canGoBack()) current.goBack() else finish()
        }
    }

    @SuppressLint("SetJavaScriptEnabled") // The pages shown need it.
    private fun createWebView(context: Context): WebView =
        WebView(context).also {
            webView = it
            it.settings.javaScriptEnabled = true
            it.settings.domStorageEnabled = true
            it.webViewClient = webViewClient
            it.clearCache(true)
            it.loadUrl(webPageUrl)
        }

    private fun onSslNoticeResult(proceed: Boolean) {
        val handler = pendingSslError ?: return
        pendingSslError = null
        if (proceed) {
            handler.proceed()
            sslNoticeUserDecision = true
        } else {
            handler.cancel()
            if (isRunningOnTv) {
                // Finish as there is no other way to display the website.
                finish()
            }
        }
    }

    /** Only offered on devices that are not TVs. */
    private fun openInBrowser() {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webPageUrl)))
        finish()
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_WEB_URL = ".activities.WebViewActivity.WEB_URL"
        private const val TAG = "WebViewActivity"
    }
}
