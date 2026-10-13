package com.nutomic.syncthingandroid.webview

import android.os.SystemClock
import java.net.InetAddress
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate

/**
 * Retries [assertion] until it passes or [timeoutMillis] runs out. Espresso does not wait for
 * WebView network loads, so checks on a page that is still loading have to be retried.
 */
fun eventually(timeoutMillis: Long = 30_000, assertion: () -> Unit) {
    val deadline = SystemClock.uptimeMillis() + timeoutMillis
    while (true) {
        try {
            assertion()
            return
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            // Espresso and Espresso-Web report "not there yet" with many exception types.
            if (SystemClock.uptimeMillis() > deadline) throw e
            SystemClock.sleep(STEP_MILLIS)
        }
    }
}

/** A self-signed certificate for 127.0.0.1, like the one Syncthing generates for its GUI. */
fun selfSignedLoopbackCertificate(): HeldCertificate =
    HeldCertificate.Builder()
        .commonName("syncthing")
        .addSubjectAlternativeName("127.0.0.1")
        .addSubjectAlternativeName("localhost")
        .build()

/** An HTTPS server on 127.0.0.1 that serves [pages] (path → HTML) and 404 for anything else. */
fun startPageServer(certificate: HeldCertificate, pages: Map<String, String>): MockWebServer =
    MockWebServer().apply {
        useHttps(
            HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory()
        )
        dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val page =
                        pages[request.url.encodedPath]
                            ?: return MockResponse.Builder().code(NOT_FOUND).build()
                    return MockResponse.Builder()
                        .addHeader("Content-Type", "text/html; charset=utf-8")
                        .body(page)
                        .build()
                }
            }
        start(InetAddress.getByName("127.0.0.1"), 0)
    }

private const val STEP_MILLIS = 100L
private const val NOT_FOUND = 404
