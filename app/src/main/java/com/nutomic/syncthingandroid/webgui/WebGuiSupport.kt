package com.nutomic.syncthingandroid.webgui

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.SslCertificate
import android.util.Base64
import android.webkit.WebView
import android.webkit.WebViewClient
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.util.ConfigXml
import java.net.URL
import java.security.cert.X509Certificate

// Helpers for WebGuiActivity that do not need the activity's state.

@SuppressLint("SetJavaScriptEnabled")
internal fun createWebView(context: Context, client: WebViewClient): WebView =
    WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // The GUI is served over the network from the local Syncthing instance; it never needs
        // to read local files, so deny file/content access to limit exfiltration if compromised.
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        webViewClient = client
        clearCache(true)
    }

internal fun loadConfiguration(context: Context): ConfigXml =
    ConfigXml(context).apply {
        try {
            loadConfig()
        } catch (e: ConfigXml.OpenConfigException) {
            throw IllegalStateException(e.message, e)
        }
    }

/**
 * Rewrites the host of the given URL to 127.0.0.1, preserving the scheme and port. The port comes
 * from the configured GUI listen address; falls back to the default web GUI port.
 *
 * Forcing loopback is intentional and security-relevant (mirrors ApiRequest.forceLoopbackHost): the
 * GUI address is only ever 127.0.0.1 or 0.0.0.0 (the latter includes loopback), so the instance is
 * always reachable here; it keeps the API key off any routable interface; and it is the
 * precondition that makes proceeding past SSL_IDMISMATCH (see WebGuiActivity.handleSslError) and
 * trusting user-installed CAs (loopback domain-config in network_security_config.xml) safe — on
 * loopback there is no MITM surface. Do not connect to the configured address directly: 0.0.0.0 is
 * not a valid destination (modern WebView blocks it) and a routable address would break the trust
 * model.
 */
internal fun toLoopback(url: URL): URL {
    val port = if (url.port != -1) url.port else Constants.DEFAULT_WEBGUI_TCP_PORT
    return URL(url.protocol, "127.0.0.1", port, url.file)
}

internal fun createAuthHeaders(username: String?, password: String?): Map<String, String> {
    val credentials = "$username:$password"
    val encodedCredentials =
        Base64.encodeToString(credentials.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    return mapOf("Authorization" to "Basic $encodedCredentials")
}

internal fun extractCertificate(sslCertificate: SslCertificate): X509Certificate? =
    sslCertificate.x509Certificate
