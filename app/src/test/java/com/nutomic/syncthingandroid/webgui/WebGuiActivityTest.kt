package com.nutomic.syncthingandroid.webgui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.http.SslCertificate
import android.net.http.SslError
import android.view.View
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.TestWebResourceError
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.testutil.ConfigFixture
import com.nutomic.syncthingandroid.testutil.RunningSyncthingService
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import com.nutomic.syncthingandroid.testutil.ShadowUtilDeviceShell
import com.nutomic.syncthingandroid.testutil.StoppedSyncthingService
import com.nutomic.syncthingandroid.util.ConfigXml
import java.security.cert.X509Certificate
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSslErrorHandler
import org.robolectric.shadows.ShadowToast
import org.robolectric.shadows.ShadowWebView

/**
 * [WebGuiActivity] around Robolectric's [ShadowWebView], which records what the activity asks of
 * the WebView but draws no web content: when the GUI is loaded, how certificate errors and links
 * are handled, and the loading and error overlays. Real page loads are in the instrumented tests.
 */
@RunWith(AndroidJUnit4::class)
@Config(
    qualifiers = "en-rUS",
    shadows = [ShadowEnvironmentWithAllFilesAccess::class, ShadowUtilDeviceShell::class],
    instrumentedPackages = ["com.nutomic.syncthingandroid.util"],
)
class WebGuiActivityTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val ca: HeldCertificate =
        HeldCertificate.Builder().certificateAuthority(0).commonName("syncthing").build()
    private lateinit var service: SyncthingService

    @Before
    fun setUp() {
        System.setProperty("os.version", "6.1.0-android")
        ConfigFixture.install(context) {
            it.replace("<address>127.0.0.1:8384</address>", "<address>0.0.0.0:8385</address>")
        }
        Constants.getHttpsCertFile(context).writeText(ca.certificatePem())
        service = StoppedSyncthingService.install()
    }

    private fun launch(): ActivityScenario<WebGuiActivity> =
        ActivityScenario.launch(WebGuiActivity::class.java)

    private fun ActivityScenario<WebGuiActivity>.webView(): WebView {
        lateinit var found: WebView
        onActivity { found = checkNotNull(it.window.decorView.findWebView()) }
        return found
    }

    private fun ActivityScenario<WebGuiActivity>.client(): WebViewClient =
        shadowOf(webView()).webViewClient

    @Test
    fun `without the HTTPS certificate the screen closes with a message`() {
        Constants.getHttpsCertFile(context).delete()

        launch().use { scenario ->
            assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, scenario.state)
        }
        assertEquals(
            "A config file crucial to operation is missing",
            ShadowToast.getTextOfLatestToast(),
        )
    }

    @Test
    fun `starting the screen starts the service`() {
        launch().use {
            val started = shadowOf(context as android.app.Application).nextStartedService
            assertEquals(SyncthingService::class.java.name, started.component?.className)
        }
    }

    @Test
    fun `the GUI waits for the service to become active`() {
        launch().use { scenario ->
            compose.onNodeWithText("Waiting for GUI").assertIsDisplayed()
            assertNull(shadowOf(scenario.webView()).lastLoadedUrl)
        }
    }

    @Test
    fun `an active service loads the GUI on loopback with the GUI credentials`() {
        launch().use { scenario ->
            RunningSyncthingService.setState(service, SyncthingService.State.ACTIVE)

            val webView = shadowOf(scenario.webView())
            val config = ConfigXml(context).apply { loadConfig() }
            assertEquals("https://127.0.0.1:8385", webView.lastLoadedUrl)
            assertEquals(
                createAuthHeaders(config.webUIUsername, config.webUIPassword),
                webView.lastAdditionalHttpHeaders,
            )
        }
    }

    @Test
    fun `the loading overlay goes away once the page has loaded`() {
        launch().use { scenario ->
            RunningSyncthingService.setState(service, SyncthingService.State.ACTIVE)
            val webView = scenario.webView()

            scenario.client().onPageFinished(webView, "https://127.0.0.1:8385/")

            compose.onNodeWithText("Waiting for GUI").assertDoesNotExist()
        }
    }

    @Test
    fun `a failed load shows the error and asks for a log`() {
        launch().use { scenario ->
            RunningSyncthingService.setState(service, SyncthingService.State.ACTIVE)
            val webView = scenario.webView()

            scenario
                .client()
                .onReceivedError(webView, request(mainFrame = true), error(-6, "net::ERR_REFUSED"))

            compose
                .onNodeWithText("Couldn't load the Web GUI. Please capture a log and report this.")
                .assertIsDisplayed()
            compose.onNodeWithText("net::ERR_REFUSED (-6)").assertIsDisplayed()
        }
    }

    @Test
    fun `errors of sub-resources or after the page loaded are ignored`() {
        launch().use { scenario ->
            RunningSyncthingService.setState(service, SyncthingService.State.ACTIVE)
            val webView = scenario.webView()
            val client = scenario.client()

            client.onReceivedError(webView, request(mainFrame = false), error(-2, "subresource"))
            client.onPageFinished(webView, "https://127.0.0.1:8385/")
            client.onReceivedError(webView, request(mainFrame = true), error(-2, "late"))

            compose.onNodeWithText("subresource (-2)").assertDoesNotExist()
            compose.onNodeWithText("late (-2)").assertDoesNotExist()
        }
    }

    @Test
    fun `GUI links stay in the WebView, other links open in another app`() {
        launch().use { scenario ->
            val webView = scenario.webView()
            val client = scenario.client()

            assertFalse(client.shouldOverrideUrlLoading(webView, request(url = GUI_PAGE)))
            assertNull(shadowOf(context as android.app.Application).nextStartedActivity)

            assertTrue(client.shouldOverrideUrlLoading(webView, request(url = DOCS_PAGE)))
            val opened = shadowOf(context as android.app.Application).nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, opened.action)
            assertEquals(Uri.parse(DOCS_PAGE), opened.data)
        }
    }

    // The deprecated overload is the one WebView calls on API 23.
    @Suppress("DEPRECATION")
    @Test
    fun `the String overload used on API 23 handles links the same way`() {
        launch().use { scenario ->
            val webView = scenario.webView()
            val client = scenario.client()

            assertFalse(client.shouldOverrideUrlLoading(webView, GUI_PAGE))
            assertTrue(client.shouldOverrideUrlLoading(webView, DOCS_PAGE))
        }
    }

    @Test
    fun `a link no app can open shows a message`() {
        launch().use { scenario ->
            shadowOf(context as android.app.Application).checkActivities(true)

            assertTrue(
                scenario
                    .client()
                    .shouldOverrideUrlLoading(scenario.webView(), request(url = DOCS_PAGE))
            )

            assertEquals("No app available to open this link.", ShadowToast.getTextOfLatestToast())
        }
    }

    @Test
    fun `a certificate signed by the local Syncthing key is accepted`() {
        val leaf = HeldCertificate.Builder().signedBy(ca).addSubjectAlternativeName("x").build()

        assertEquals(PROCEED, sslDecision(SslError.SSL_UNTRUSTED, leaf.certificate))
    }

    @Test
    fun `a certificate signed by any other key is refused`() {
        val stranger = HeldCertificate.Builder().commonName("mitm").build()

        assertEquals(CANCEL, sslDecision(SslError.SSL_UNTRUSTED, stranger.certificate))
    }

    @Test
    fun `a refused certificate shows the load error`() {
        launch().use { scenario ->
            RunningSyncthingService.setState(service, SyncthingService.State.ACTIVE)
            val webView = scenario.webView()
            val client = scenario.client()
            val stranger = HeldCertificate.Builder().commonName("mitm").build().certificate
            val handler = Shadow.newInstanceOf(SslErrorHandler::class.java)

            client.onReceivedSslError(
                webView,
                handler,
                SslError(SslError.SSL_UNTRUSTED, SslCertificate(stranger), GUI_PAGE),
            )
            // Chromium then reports the cancelled load as finished, without onReceivedError.
            client.onPageFinished(webView, GUI_PAGE)

            compose
                .onNodeWithText("Couldn't load the Web GUI. Please capture a log and report this.")
                .assertIsDisplayed()
        }
    }

    @Test
    fun `a trusted certificate for another host name is accepted`() {
        val stranger = HeldCertificate.Builder().commonName("user-ca-signed").build()

        assertEquals(PROCEED, sslDecision(SslError.SSL_IDMISMATCH, stranger.certificate))
    }

    @Test
    fun `an expired certificate for another host name still has to be ours`() {
        val stranger = HeldCertificate.Builder().commonName("user-ca-signed").build()

        val decision =
            sslDecision(SslError.SSL_IDMISMATCH, stranger.certificate) {
                it.addError(SslError.SSL_EXPIRED)
            }

        assertEquals(CANCEL, decision)
    }

    @Test
    fun `back goes back in the GUI first, then closes the screen`() {
        launch().use { scenario ->
            val webView = shadowOf(scenario.webView())
            webView.setCanGoBack(true)

            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            assertEquals(1, webView.goBackInvocations)

            webView.setCanGoBack(false)
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            scenario.onActivity { assertTrue(it.isFinishing) }
        }
    }

    @Test
    fun `the top bar arrow closes the screen`() {
        launch().use { scenario ->
            compose.onNodeWithContentDescription("Back").performClick()

            scenario.onActivity { assertTrue(it.isFinishing) }
        }
    }

    @Test
    fun `closing the screen destroys the WebView`() {
        val scenario = launch()
        val webView = scenario.webView()

        scenario.close()

        assertTrue(shadowOf(webView).wasDestroyCalled())
    }

    /** Runs a certificate error for the GUI page through the activity and reports its decision. */
    private fun sslDecision(
        primaryError: Int,
        certificate: X509Certificate,
        configure: (SslError) -> Unit = {},
    ): String {
        launch().use { scenario ->
            val handler = Shadow.newInstanceOf(SslErrorHandler::class.java)
            val error = SslError(primaryError, SslCertificate(certificate), GUI_PAGE)
            configure(error)

            scenario.client().onReceivedSslError(scenario.webView(), handler, error)

            val shadow = Shadow.extract<ShadowSslErrorHandler>(handler)
            return when {
                shadow.wasProceedCalled() -> PROCEED
                shadow.wasCancelCalled() -> CANCEL
                else -> "undecided"
            }
        }
    }

    private fun request(mainFrame: Boolean = true, url: String = GUI_PAGE): WebResourceRequest =
        object : WebResourceRequest {
            override fun getUrl(): Uri = Uri.parse(url)

            override fun isForMainFrame(): Boolean = mainFrame

            override fun isRedirect(): Boolean = false

            override fun hasGesture(): Boolean = true

            override fun getMethod(): String = "GET"

            override fun getRequestHeaders(): Map<String, String> = emptyMap()
        }

    private fun error(code: Int, description: String): WebResourceError =
        TestWebResourceError(code, description)

    private companion object {
        const val GUI_PAGE = "https://127.0.0.1:8385/#settings"
        const val DOCS_PAGE = "https://docs.syncthing.net/"
        const val PROCEED = "proceed"
        const val CANCEL = "cancel"

        fun View.findWebView(): WebView? =
            when (this) {
                is WebView -> this
                is ViewGroup ->
                    (0 until childCount).firstNotNullOfOrNull { getChildAt(it).findWebView() }
                else -> null
            }
    }
}
