@file:JvmName("OsTrustStore")

package com.nutomic.syncthingandroid.util

import android.util.Log
import java.security.KeyStore
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

private const val TAG = "OsTrustStore"

/**
 * Cached [X509TrustManager] backed by the Android OS trust store ("AndroidCAStore"), which
 * aggregates both the system CAs and the CAs the user manually installed. Built once.
 */
private val lazyOsTrustManager: X509TrustManager? by lazy {
    try {
        val caStore = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(caStore) }
            .trustManagers
            .filterIsInstance<X509TrustManager>()
            .firstOrNull()
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        // Any failure, checked or not, means there is no OS trust store to fall back to.
        Log.w(TAG, "getOsTrustManager: Failed to build OS trust manager", e)
        null
    }
}

/**
 * An [X509TrustManager] that validates against the Android OS trust store, including user-installed
 * CAs. Unlike a `TrustManagerFactory.init((KeyStore) null)`, the "AndroidCAStore" keystore exposes
 * user-added certificates on API 24+.
 *
 * Used as a fallback so the app can talk to a local Syncthing instance whose HTTPS certificate was
 * replaced with one signed by a CA the user trusts at the OS level (see
 * https://github.com/researchxxl/syncthing-android/issues/222).
 *
 * @return the OS-backed trust manager, or null if it could not be built.
 */
fun getOsTrustManager(): X509TrustManager? = lazyOsTrustManager
