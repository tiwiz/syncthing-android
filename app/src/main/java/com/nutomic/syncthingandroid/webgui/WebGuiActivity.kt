package com.nutomic.syncthingandroid.webgui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Proxy
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcelable
import android.util.ArrayMap
import android.util.Log
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.activities.SyncthingActivity
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.service.SyncthingServiceBinder
import com.nutomic.syncthingandroid.theme.ApplicationTheme
import com.nutomic.syncthingandroid.util.ConfigXml
import com.nutomic.syncthingandroid.util.Util
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.net.URL
import java.security.GeneralSecurityException
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import androidx.core.net.toUri

/**
 * Holds a WebView that shows the web ui of the local syncthing instance.
 */
class WebGuiActivity : SyncthingActivity(), SyncthingService.OnServiceStateChangeListener {

    private lateinit var config: ConfigXml

    /**
     * Web GUI URL pinned to the loopback interface. The WebView only ever talks to the local
     * Syncthing instance, so we connect on 127.0.0.1 regardless of the configured GUI listen
     * address (which is 0.0.0.0 when remote access is enabled). This also keeps the WebView within
     * the loopback domain-config in network_security_config.xml that trusts user-installed CAs.
     */
    private lateinit var webGuiUrl: URL
    private var caCertificate: X509Certificate? = null
    private var registeredService: SyncthingService? = null
    private var webView: WebView? = null
    private var webGuiLoadStarted = false
    private var serviceActive = false
    private var webGuiLoading by mutableStateOf(true)
    private var webGuiLoadError by mutableStateOf<String?>(null)

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
            if (!request.isForMainFrame || !webGuiLoading) {
                return
            }
            Log.w(TAG, "Web GUI load failed: ${error.errorCode} ${error.description}")
            webGuiLoadError = "${error.description} (${error.errorCode})"
        }

        override fun onPageFinished(view: WebView, url: String) {
            webGuiLoading = false
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
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        config = loadConfiguration(this)
        webGuiUrl = toLoopback(config.webGuiUrl)
        if (!loadCaCertificate()) {
            return
        }

        setContent {
            ApplicationTheme {
                WebGuiScreen(
                    loading = webGuiLoading,
                    error = webGuiLoadError,
                    onNavigateBack = { finish() },
                    webViewFactory = { context ->
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
    }

    override fun onServiceConnected(componentName: ComponentName, binder: IBinder) {
        super.onServiceConnected(componentName, binder)
        registerServiceListener((binder as SyncthingServiceBinder).service)
    }

    override fun onServiceDisconnected(componentName: ComponentName) {
        unregisterServiceListener()
        super.onServiceDisconnected(componentName)
    }

    override fun onServiceStateChange(newState: SyncthingService.State) {
        Log.v(TAG, "onServiceStateChange($newState)")
        serviceActive = newState == SyncthingService.State.ACTIVE
        loadWebGuiIfNeeded()
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
        unregisterServiceListener()
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    private fun loadCaCertificate(): Boolean {
        val httpsCertFile: File = Constants.getHttpsCertFile(this)
        if (!httpsCertFile.exists()) {
            Toast.makeText(this, R.string.config_file_missing, Toast.LENGTH_LONG).show()
            finish()
            return false
        }

        try {
            FileInputStream(httpsCertFile).use { input: InputStream ->
                val certificateFactory = CertificateFactory.getInstance("X.509")
                caCertificate = certificateFactory.generateCertificate(input) as X509Certificate
            }
            return true
        } catch (e: FileNotFoundException) {
            throw IllegalArgumentException("Untrusted Certificate", e)
        } catch (e: CertificateException) {
            throw IllegalArgumentException("Untrusted Certificate", e)
        }
    }

    private fun startSyncthingService() {
        val serviceIntent = Intent(this, SyncthingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun registerServiceListener(service: SyncthingService) {
        unregisterServiceListener()
        registeredService = service
        service.registerOnServiceStateChangeListener(this)
    }

    private fun unregisterServiceListener() {
        registeredService?.unregisterOnServiceStateChangeListener(this)
        registeredService = null
    }

    private fun loadWebGuiIfNeeded() {
        val currentWebView = webView
        if (currentWebView == null) {
            Log.v(TAG, "loadWebGuiIfNeeded: Skipped event due to webView == null")
            return
        }
        if (!serviceActive || webGuiLoadStarted || currentWebView.url != null) {
            return
        }

        webGuiLoadStarted = true
        currentWebView.stopLoading()
        setWebViewProxy(
            currentWebView.context.applicationContext,
            "",
            0,
            WEB_VIEW_PROXY_EXCLUSIONS,
        )
        currentWebView.loadUrl(
            webGuiUrl.toString(),
            createAuthHeaders(config.webUIUsername, config.webUIPassword),
        )
    }

    private fun shouldOpenOutsideWebView(uri: Uri): Boolean {
        val host = uri.host
        val webGuiHost = webGuiUrl.host
        if (host != null && host == webGuiHost) {
            return false
        }

        if (!Util.isRunningOnTV(this)) {
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
        try {
            val certificate = extractCertificate(error.certificate)
            val ca = caCertificate
            if (certificate == null || ca == null) {
                Log.w(TAG, "X509Certificate reference invalid")
                handler.cancel()
                return
            }
            certificate.verify(ca.publicKey)
            handler.proceed()
        } catch (e: ReflectiveOperationException) {
            // Thrown while reflecting the certificate out of SslCertificate.
            Log.w(TAG, e)
            handler.cancel()
        } catch (e: GeneralSecurityException) {
            // Thrown by X509Certificate.verify() when the cert is not signed by our CA.
            Log.w(TAG, e)
            handler.cancel()
        }
    }

    companion object {
        private const val TAG = "WebGuiActivity"
        private const val WEB_VIEW_PROXY_EXCLUSIONS = "localhost|0.0.0.0|127.*|[::1]"

        /**
         * Set WebView proxy and sites that are not retrieved using proxy.
         * Compatible with KitKat or higher android version.
         * Returns boolean if successful.
         * Source: https://stackoverflow.com/a/26781539
         *
         * It reflects into hidden framework classes whose shape differs between Android versions and
         * vendors, so any exception only means the proxy could not be set: hence the broad catch.
         */
        @Suppress("TooGenericExceptionCaught")
        @SuppressLint("PrivateApi")
        private fun setWebViewProxy(
            appContext: Context,
            host: String,
            port: Int,
            exclusionList: String,
        ): Boolean {
            val properties = System.getProperties()
            properties.setProperty("http.proxyHost", host)
            properties.setProperty("http.proxyPort", port.toString())
            properties.setProperty("https.proxyHost", host)
            properties.setProperty("https.proxyPort", port.toString())
            properties.setProperty("http.nonProxyHosts", exclusionList)
            properties.setProperty("https.nonProxyHosts", exclusionList)

            try {
                val applicationClass = Class.forName("android.app.Application")
                val loadedApkField: Field = applicationClass.getDeclaredField("mLoadedApk")
                loadedApkField.isAccessible = true
                val loadedApk = loadedApkField.get(appContext)
                val loadedApkClass = Class.forName("android.app.LoadedApk")
                val receiversField = loadedApkClass.getDeclaredField("mReceivers")
                receiversField.isAccessible = true
                val receivers = receiversField.get(loadedApk) as ArrayMap<*, *>
                for (receiverMap in receivers.values) {
                    for (receiver in (receiverMap as ArrayMap<*, *>).keys) {
                        val clazz = receiver?.javaClass ?: continue
                        if (clazz.name.contains("ProxyChangeListener")) {
                            val onReceiveMethod: Method = clazz.getDeclaredMethod(
                                "onReceive",
                                Context::class.java,
                                Intent::class.java,
                            )
                            val intent = Intent(Proxy.PROXY_CHANGE_ACTION)

                            val proxyInfoClass = Class.forName("android.net.ProxyInfo")
                            val constructor: Constructor<*> = proxyInfoClass.getConstructor(
                                String::class.java,
                                Integer.TYPE,
                                String::class.java,
                            )
                            constructor.isAccessible = true
                            val proxyProperties = constructor.newInstance(
                                host,
                                port,
                                exclusionList,
                            )
                            intent.putExtra("proxy", proxyProperties as Parcelable)

                            onReceiveMethod.invoke(receiver, appContext, intent)
                        }
                    }
                }
                return true
            } catch (e: Exception) {
                Log.w(TAG, "setWebViewProxy exception", e)
            }
            return false
        }
    }
}
