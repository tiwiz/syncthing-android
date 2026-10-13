package com.nutomic.syncthingandroid.webgui

import android.content.Context
import android.net.http.SslCertificate
import android.util.Base64
import android.webkit.WebViewClient
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.testutil.ConfigFixture
import com.nutomic.syncthingandroid.testutil.Fixtures
import java.net.URL
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The web GUI helpers that do not need WebGuiActivity's state. */
@RunWith(AndroidJUnit4::class)
class WebGuiSupportTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the GUI is always reached on loopback, keeping scheme, port and path`() {
        assertEquals(URL("https://127.0.0.1:8384"), toLoopback(URL("https://0.0.0.0:8384")))
        assertEquals(URL("https://127.0.0.1:8385/"), toLoopback(URL("https://192.0.2.5:8385/")))
        assertEquals(
            URL("http://127.0.0.1:9999/a?b=c"),
            toLoopback(URL("http://localhost:9999/a?b=c")),
        )
    }

    @Test
    fun `a URL without a port gets the default GUI port`() {
        assertEquals(
            URL("https://127.0.0.1:${Constants.DEFAULT_WEBGUI_TCP_PORT}"),
            toLoopback(URL("https://0.0.0.0")),
        )
    }

    @Test
    fun `basic auth header`() {
        val header = createAuthHeaders("admin", "sécret:1")

        val encoded = header.getValue("Authorization").removePrefix("Basic ")
        assertEquals(setOf("Authorization"), header.keys)
        assertEquals(
            "admin:sécret:1",
            String(Base64.decode(encoded, Base64.NO_WRAP), Charsets.UTF_8),
        )
    }

    // Missing credentials are sent as the text "null"; without a GUI user Syncthing ignores them.
    @Test
    fun `missing credentials become the text null`() {
        val encoded = createAuthHeaders(null, null).getValue("Authorization").removePrefix("Basic ")

        assertEquals("null:null", String(Base64.decode(encoded, Base64.NO_WRAP), Charsets.UTF_8))
    }

    @Test
    fun `the X509 certificate is read back out of an SslCertificate`() {
        val certificate =
            CertificateFactory.getInstance("X.509")
                .generateCertificate(Fixtures.bytes("certs/rsa-cert.pem").inputStream())
                as X509Certificate

        assertSame(certificate, extractCertificate(SslCertificate(certificate)))
    }

    @Test
    fun `the configuration is loaded, or a missing one is an IllegalStateException`() {
        assertThrows(IllegalStateException::class.java) { loadConfiguration(context) }

        System.setProperty("os.version", "6.1.0-android")
        ConfigFixture.install(context)
        assertEquals("fixture-api-key", loadConfiguration(context).apiKey)
    }

    @Test
    fun `the web view runs scripts but cannot read local files`() {
        val client = WebViewClient()

        val webView = createWebView(context, client)

        assertTrue(webView.settings.javaScriptEnabled)
        assertTrue(webView.settings.domStorageEnabled)
        assertFalse(webView.settings.allowFileAccess)
        assertFalse(webView.settings.allowContentAccess)
        assertSame(client, webView.webViewClient)
    }
}
