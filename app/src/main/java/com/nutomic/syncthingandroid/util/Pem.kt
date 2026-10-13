package com.nutomic.syncthingandroid.util

import com.google.common.io.BaseEncoding
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

// PEM helpers for CertificateValidator.

internal fun parseChain(bytes: ByteArray): List<X509Certificate> {
    val cf = CertificateFactory.getInstance("X.509")
    return cf.generateCertificates(ByteArrayInputStream(bytes)).map { it as X509Certificate }
}

/**
 * Uses Guava rather than `android.util.Base64` deliberately: it keeps this code free of Android
 * APIs so it can be unit-tested on the JVM without Robolectric. `java.util.Base64` is not an option
 * — it needs API 26 and `min-sdk` is 23.
 */
internal fun pemToDer(pem: ByteArray, kind: String): ByteArray {
    val text = String(pem, Charsets.US_ASCII)
    val begin = "-----BEGIN $kind-----"
    val end = "-----END $kind-----"
    val b = text.indexOf(begin)
    val e = text.indexOf(end)
    require(b >= 0 && e > b) { "PEM block not found" }
    val body = text.substring(b + begin.length, e).replace("\\s".toRegex(), "")
    return BaseEncoding.base64().decode(body)
}

internal fun looksLikeCertificate(bytes: ByteArray): Boolean =
    String(bytes, Charsets.US_ASCII).contains("-----BEGIN CERTIFICATE-----")

internal fun looksLikeKey(bytes: ByteArray): Boolean =
    String(bytes, Charsets.US_ASCII).contains("PRIVATE KEY-----")
