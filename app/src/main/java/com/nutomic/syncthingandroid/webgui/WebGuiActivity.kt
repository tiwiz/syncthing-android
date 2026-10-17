package com.nutomic.syncthingandroid.webgui

import com.nutomic.syncthingandroid.util.isRunningOnTV
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.activities.SyncthingActivity
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.theme.ApplicationTheme
import java.security.GeneralSecurityException
import androidx.core.net.toUri

/**
 * Holds a WebView that shows the web ui of the local syncthing instance.
 */
@AndroidEntryPoint
class WebGuiActivity : SyncthingActivity() {

    private val viewModel: WebGuiViewModel by viewModels()

    private var webView: WebView? = null
    private var webGuiLoadStarted = false

    private val webViewClient = object : WebViewClient() {
        override fun onReceivedSslError(
            view: WebView,
            handler: SslErrorHandler,
            error: SslError,
        ) {
            handleSslError(handler, error)
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean = shouldOpenOutsideWebView(request.url)

        // The WebResourceRequest overload above is only dispatched on API >= 24; minSdk is 23,
        // so the deprecated String overload is required to intercept links on API 23 devices.
        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
            shouldOpenOutsideWebView(url.toUri())

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            if (!request.isForMainFrame || !viewModel.loadState.value.loading) {
                return
            }
            Log.w(TAG, "Web GUI load failed: ${error.errorCode} ${error.description}")
            viewModel.onLoadError("${error.description} (${error.errorCode})")
        }

        override fun onPageFinished(view: WebView, url: String) {
            viewModel.onPageFinished()
        }
    }

    private val backPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            val currentWebView = webView
            if (currentWebView != null && currentWebView.canGoBack()) {
                currentWebView.goBack()
            } else {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (viewModel.caCertificate == null) {
            Toast.makeText(this, R.string.config_file_missing, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setContent {
            val loadState by viewModel.loadState.collectAsStateWithLifecycle()
            ApplicationTheme {
                WebGuiScreen(
                    loading = loadState.loading,
                    error = loadState.error,
                    onNavigateBack = { finish() },
                    webViewFactory = { context ->
                        viewModel.onWebViewCreated()
                        createWebView(context, webViewClient).also {
                            webView = it
                            // The WebView is created lazily during composition, which may run
                            // after the service already reported ACTIVE. Attempt the load here so
                            // we don't miss that initial state and stay stuck on the spinner.
                            loadWebGuiIfNeeded()
                        }
                    },
                )
            }
        }

        startSyncthingService()
        onBackPressedDispatcher.addCallback(this, backPressedCallback)
        lifecycleScope.launch {
            viewModel.serviceActive.collect { active ->
                Log.v(TAG, "serviceActive($active)")
                loadWebGuiIfNeeded()
            }
        }
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
        if (webGuiLoadStarted && WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            ProxyController.getInstance().clearProxyOverride(ContextCompat.getMainExecutor(this)) {}
        }
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    private fun startSyncthingService() {
        val serviceIntent = Intent(this, SyncthingService::class.java)
        startForegroundService(serviceIntent)
    }

    private fun loadWebGuiIfNeeded() {
        val currentWebView = webView
        if (currentWebView == null) {
            Log.v(TAG, "loadWebGuiIfNeeded: Skipped event due to webView == null")
            return
        }
        if (!viewModel.serviceActive.value || webGuiLoadStarted || currentWebView.url != null) {
            return
        }

        webGuiLoadStarted = true
        currentWebView.stopLoading()
        bypassProxies {
            webView?.loadUrl(
                viewModel.webGuiUrl.toString(),
                createAuthHeaders(viewModel.config.webUIUsername, viewModel.config.webUIPassword),
            )
        }
    }

    /**
     * The web GUI is on loopback, so a system or Wi-Fi proxy must not handle it (#440). The WebView
     * here only loads 127.0.0.1 (other links open outside the app), so while this activity lives
     * every WebView connects directly; [onDestroy] gives the proxy back. Runs [then] once the
     * override is in place.
     */
    private fun bypassProxies(then: () -> Unit) {
        setJvmProxyProperties()
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            Log.w(TAG, "bypassProxies: WebView does not support proxy override")
            then()
            return
        }
        ProxyController.getInstance()
            .setProxyOverride(
                ProxyConfig.Builder().addDirect().build(),
                ContextCompat.getMainExecutor(this),
                then,
            )
    }

    private fun shouldOpenOutsideWebView(uri: Uri): Boolean {
        val host = uri.host
        val webGuiHost = viewModel.webGuiUrl.host
        if (host != null && host == webGuiHost) {
            return false
        }

        if (!isRunningOnTV(this)) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, R.string.no_app_to_open_link, Toast.LENGTH_SHORT).show()
            }
        }
        return true
    }

    private fun handleSslError(handler: SslErrorHandler, error: SslError) {
        // The WebView only ever connects to the local Syncthing instance over loopback. When the
        // certificate chain is trusted by the OS (see the loopback domain-config in
        // network_security_config.xml) but the hostname does not match — e.g. a user-supplied
        // CA-signed certificate without a 127.0.0.1 SAN — the only remaining error is
        // SSL_IDMISMATCH. Proceed in that case, mirroring the REST API path which likewise skips
        // hostname verification for the local connection. Untrusted, expired, and not-yet-valid
        // certificates are not accepted here; they fall through to self-signed pinning below.
        //
        // Checking primaryError == SSL_IDMISMATCH is sufficient to require a trusted chain:
        // SslError.primaryError returns the highest-severity error present, and SSL_UNTRUSTED
        // outranks SSL_IDMISMATCH, so an untrusted chain reports SSL_UNTRUSTED here (not
        // SSL_IDMISMATCH) and is rejected. The explicit expired / not-yet-valid guards then exclude
        // the remaining date errors that can coexist with a mismatch.
        if (error.primaryError == SslError.SSL_IDMISMATCH &&
            !error.hasError(SslError.SSL_EXPIRED) &&
            !error.hasError(SslError.SSL_NOTYETVALID)
        ) {
            handler.proceed()
            return
        }

        // Otherwise, fall back to pinning against the local instance's self-signed certificate.
        val pinned = try {
            val certificate = extractCertificate(error.certificate)
            val ca = viewModel.caCertificate
            if (certificate == null || ca == null) {
                Log.w(TAG, "X509Certificate reference invalid")
                false
            } else {
                certificate.verify(ca.publicKey)
                true
            }
        } catch (e: ReflectiveOperationException) {
            // Thrown while reflecting the certificate out of SslCertificate.
            Log.w(TAG, e)
            false
        } catch (e: GeneralSecurityException) {
            // Thrown by X509Certificate.verify() when the cert is not signed by our CA.
            Log.w(TAG, e)
            false
        }
        if (pinned) {
            handler.proceed()
            return
        }
        handler.cancel()
        // The WebView then reports the page as finished without calling onReceivedError, so show
        // the load error here, or the screen would stay blank.
        viewModel.onLoadError("Untrusted certificate (SSL error ${error.primaryError})")
    }

    companion object {
        private const val TAG = "WebGuiActivity"
        private const val WEB_VIEW_PROXY_EXCLUSIONS = "localhost|0.0.0.0|127.*|[::1]"

        /**
         * Keeps Java's own HTTP stack off proxies for loopback, as the app always did next to the
         * WebView proxy override.
         */
        private fun setJvmProxyProperties() {
            val properties = System.getProperties()
            properties.setProperty("http.proxyHost", "")
            properties.setProperty("http.proxyPort", "0")
            properties.setProperty("https.proxyHost", "")
            properties.setProperty("https.proxyPort", "0")
            properties.setProperty("http.nonProxyHosts", WEB_VIEW_PROXY_EXCLUSIONS)
            properties.setProperty("https.nonProxyHosts", WEB_VIEW_PROXY_EXCLUSIONS)
        }
    }
}
