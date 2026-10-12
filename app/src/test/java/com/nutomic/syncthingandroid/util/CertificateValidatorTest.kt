package com.nutomic.syncthingandroid.util

import com.nutomic.syncthingandroid.testutil.Fixtures
import com.nutomic.syncthingandroid.testutil.Golden
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Plain JVM test: the validator only uses JDK crypto, and takes the trust store as a parameter. */
class CertificateValidatorTest {

    private lateinit var savedLocale: Locale
    private lateinit var savedZone: TimeZone

    @Before
    fun pinLocaleAndZone() {
        savedLocale = Locale.getDefault()
        savedZone = TimeZone.getDefault()
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreLocaleAndZone() {
        Locale.setDefault(savedLocale)
        TimeZone.setDefault(savedZone)
    }

    private fun cert(name: String) = Fixtures.bytes("certs/$name")

    private fun trustManagerFor(caPem: ByteArray?): X509TrustManager {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        if (caPem != null) {
            val ca =
                CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(caPem))
                    as X509Certificate
            store.setCertificateEntry("fixture-ca", ca)
        }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(store)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private fun describe(label: String, certSlot: String, keySlot: String, trust: X509TrustManager?): String {
        val result = CertificateValidator.validate(cert(certSlot), cert(keySlot), trust)
        return buildString {
            appendLine("## $label ($certSlot + $keySlot)")
            appendLine("parseError: ${result.parseError}")
            appendLine("canApply: ${result.canApply}")
            appendLine("info: ${result.info}")
            appendLine("certPem is ${if (result.certPem.contentEquals(cert(certSlot))) "certSlot" else "keySlot"}")
            result.checks.forEach { appendLine("${it.check}: ${it.status} — ${it.detail}") }
        }
    }

    @Test
    fun `validation results for every scenario`() {
        val ca = cert("ca-cert.pem")
        val trustsCa = trustManagerFor(ca)
        val trustsNothing = trustManagerFor(null)
        val report =
            listOf(
                    describe("self-signed RSA", "rsa-cert.pem", "rsa-key.pem", trustsNothing),
                    describe("self-signed EC", "ec-cert.pem", "ec-key.pem", trustsNothing),
                    describe("slots swapped", "rsa-key.pem", "rsa-cert.pem", trustsNothing),
                    describe("legacy PKCS#1 key", "rsa-cert.pem", "rsa-key-pkcs1.pem", trustsNothing),
                    describe("key of another certificate", "rsa-cert.pem", "other-rsa-key.pem", trustsNothing),
                    describe("expired", "expired-cert.pem", "expired-key.pem", trustsNothing),
                    describe("not yet valid", "future-cert.pem", "future-key.pem", trustsNothing),
                    describe("CA-signed chain, CA trusted", "leaf-chain.pem", "leaf-key.pem", trustsCa),
                    describe("CA-signed chain, CA not trusted", "leaf-chain.pem", "leaf-key.pem", trustsNothing),
                    describe("CA-signed leaf without chain, CA trusted", "leaf-cert.pem", "leaf-key.pem", trustsCa),
                    describe("CA-signed chain, no trust store", "leaf-chain.pem", "leaf-key.pem", null),
                    describe("two certificates", "rsa-cert.pem", "ec-cert.pem", trustsNothing),
                    describe("two keys", "rsa-key.pem", "ec-key.pem", trustsNothing),
                    describe("garbage", "garbage.txt", "garbage.txt", trustsNothing),
                    describe("certificate and garbage", "rsa-cert.pem", "garbage.txt", trustsNothing),
                )
                .joinToString("\n")
        Golden.assertMatches("util/certificate-validator.txt", report)
    }

    @Test
    fun `describe and fingerprint`() {
        val info = CertificateValidator.describe(cert("leaf-chain.pem"))
        assertNotNull(info)
        assertEquals("syncthing.example", info!!.subject)
        assertNull(CertificateValidator.describe(cert("garbage.txt")))

        val fingerprint = CertificateValidator.leafFingerprint(cert("leaf-chain.pem"))
        assertEquals(fingerprint, CertificateValidator.leafFingerprint(cert("leaf-cert.pem")))
        assertEquals(64, fingerprint!!.length)
        assertNull(CertificateValidator.leafFingerprint(cert("garbage.txt")))
    }
}
