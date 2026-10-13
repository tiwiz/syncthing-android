package com.nutomic.syncthingandroid.http

import android.content.Context
import android.net.Uri
import android.util.Log
import com.android.volley.DefaultRetryPolicy
import com.android.volley.NetworkResponse
import com.android.volley.RequestQueue
import com.android.volley.Response
import com.android.volley.VolleyError
import com.android.volley.toolbox.HttpHeaderParser
import com.android.volley.toolbox.HurlStack
import com.android.volley.toolbox.StringRequest
import com.android.volley.toolbox.Volley
import com.nutomic.syncthingandroid.service.Constants
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.nio.charset.Charset
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory

sealed class ApiRequest(
    private val context: Context,
    url: URL,
    private val path: String,
    private val apiKey: String?,
) {

    fun interface OnSuccessListener {
        fun onSuccess(result: String)
    }

    fun interface OnErrorListener {
        fun onError(error: VolleyError)
    }

    /**
     * The app only ever talks to the local syncthing instance. Pin the connection to the loopback
     * interface regardless of the configured GUI listen address (which is 0.0.0.0 when remote
     * access is enabled), keeping the API key and config off any routable interface.
     */
    private val url: URL = forceLoopbackHost(url)

    private val volleyQueue: RequestQueue
        get() =
            sVolleyQueue
                ?: Volley.newRequestQueue(context.applicationContext, NetworkStack()).also {
                    sVolleyQueue = it
                }

    internal fun buildUri(params: Map<String, String>): Uri {
        val uriBuilder = Uri.parse(url.toString()).buildUpon().path(path)
        for ((key, value) in params) {
            uriBuilder.appendQueryParameter(key, value)
        }
        return uriBuilder.build()
    }

    /** Opens the connection, then hands the response string or the error to the listeners. */
    internal fun connect(
        requestMethod: Int,
        uri: Uri,
        requestBody: String?,
        listener: OnSuccessListener?,
        errorListener: OnErrorListener?,
    ) {
        val request =
            object :
                StringRequest(
                    requestMethod,
                    uri.toString(),
                    { reply -> listener?.onSuccess(reply) },
                    { error -> onRequestError(uri, error, errorListener) },
                ) {
                override fun getHeaders(): Map<String, String> =
                    mapOf(HEADER_API_KEY to checkNotNull(apiKey))

                override fun getBody(): ByteArray? = requestBody?.toByteArray()

                override fun parseNetworkResponse(response: NetworkResponse): Response<String> =
                    Response.success(
                        String(response.data, responseCharset(response.headers)),
                        HttpHeaderParser.parseCacheHeaders(response),
                    )
            }
        // Some requests seem to be slow or fail, make sure this doesn't break the app
        // (eg if an event request fails, new event requests won't be triggered).
        request.retryPolicy =
            DefaultRetryPolicy(TIMEOUT_MILLIS, MAX_RETRIES, DefaultRetryPolicy.DEFAULT_BACKOFF_MULT)
        volleyQueue.add(request)
    }

    /** Uses [sslSocketFactory] and disables hostname verification. */
    private inner class NetworkStack : HurlStack(null, sslSocketFactory) {
        override fun createConnection(url: URL): HttpURLConnection {
            val connection = super.createConnection(url)
            if (this@ApiRequest.url.toString().startsWith("https://")) {
                // Safe to skip hostname verification: the connection is pinned to the loopback
                // interface (see forceLoopbackHost), so there is no network MITM surface and the
                // certificate's SAN/CN need not match 127.0.0.1 (a user-supplied CA cert is
                // typically issued for a real hostname, not the loopback address). Trust is still
                // enforced by SyncthingTrustManager: the self-signed pin first, then the OS trust
                // store.
                (connection as HttpsURLConnection).setHostnameVerifier { _, _ -> true }
            }
            return connection
        }
    }

    private val sslSocketFactory: SSLSocketFactory?
        get() =
            try {
                SSLContext.getInstance("TLS")
                    .apply {
                        init(
                            null,
                            arrayOf(SyncthingTrustManager(Constants.getHttpsCertFile(context))),
                            SecureRandom(),
                        )
                    }
                    .socketFactory
            } catch (e: GeneralSecurityException) {
                Log.w(TAG, e)
                null
            }

    companion object {
        private const val TAG = "ApiRequest"

        /** The name of the HTTP header used for the syncthing API key. */
        private const val HEADER_API_KEY = "X-API-Key"

        private const val TIMEOUT_MILLIS = 5000
        private const val MAX_RETRIES = 5

        /** One queue for the process; tests reset it through this field by reflection. */
        private var sVolleyQueue: RequestQueue? = null

        private fun onRequestError(uri: Uri, error: VolleyError, errorListener: OnErrorListener?) {
            if (errorListener != null) {
                errorListener.onError(error)
                return
            }
            val statusCode = error.networkResponse?.statusCode ?: 0
            Log.w(TAG, "Request to $uri failed, code=$statusCode, msg=${error.message}")
        }

        /**
         * The charset of a response: the explicit one, else UTF-8 for JSON, else Volley's default
         * ISO-8859-1.
         */
        private fun responseCharset(headers: Map<String, String>?): Charset {
            if (headers == null) {
                return Charsets.ISO_8859_1
            }
            val parsedCharset = HttpHeaderParser.parseCharset(headers, null)
            if (parsedCharset != null) {
                return Charset.forName(parsedCharset)
            }
            val contentType = headers["Content-Type"]
            return if (contentType?.lowercase(Locale.US)?.startsWith("application/json") == true) {
                Charsets.UTF_8
            } else {
                Charsets.ISO_8859_1
            }
        }

        /**
         * Rewrites the host of the given URL to 127.0.0.1, preserving the scheme and port. The port
         * comes from the configured GUI listen address; falls back to the default web GUI port.
         *
         * Forcing loopback is intentional and security-relevant, not merely a convenience:
         * - The app only ever sets the GUI address to 127.0.0.1 or 0.0.0.0 (the "listen on all
         *   interfaces" setting). 0.0.0.0 always includes loopback, so the local instance is
         *   reachable on 127.0.0.1 in every app-managed config.
         * - It keeps the API key and configuration off any routable interface.
         * - It is the precondition that makes the two TLS relaxations safe: disabling hostname
         *   verification ([NetworkStack.createConnection]) and falling back to the OS trust store /
         *   user-installed CAs ([SyncthingTrustManager]). On loopback there is no network position
         *   for a MITM to occupy, so neither relaxation can be abused.
         *
         * Do not "simplify" this by connecting to the configured address directly: 0.0.0.0 is not a
         * valid destination (and modern WebView blocks it), and targeting a routable address would
         * break the trust model above.
         */
        private fun forceLoopbackHost(url: URL): URL =
            try {
                val port = if (url.port != -1) url.port else Constants.DEFAULT_WEBGUI_TCP_PORT
                URL(url.protocol, "127.0.0.1", port, url.file)
            } catch (e: MalformedURLException) {
                Log.w(TAG, "forceLoopbackHost: Failed to rewrite host, using original URL", e)
                url
            }
    }
}
