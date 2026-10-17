package com.nutomic.syncthingandroid.activities

import android.app.Application
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.net.http.SslCertificate
import android.net.http.SslError
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.WebView
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.testutil.ConfigFixture
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import com.nutomic.syncthingandroid.testutil.ShadowUtilDeviceShell
import com.nutomic.syncthingandroid.testutil.StoppedSyncthingService
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSslErrorHandler

/**
 * [WebViewActivity], which shows a web page such as the usage statistics, around Robolectric's
 * WebView: loading, the certificate warning and leaving for the browser.
 */
@RunWith(AndroidJUnit4::class)
@Config(
    qualifiers = "en-rUS",
    shadows = [ShadowEnvironmentWithAllFilesAccess::class, ShadowUtilDeviceShell::class],
    instrumentedPackages = ["com.nutomic.syncthingandroid.util"],
)
class WebViewActivityTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        System.setProperty("os.version", "6.1.0-android")
        ConfigFixture.install(context)
        StoppedSyncthingService.install()
    }

    private fun launch(url: String? = PAGE): ActivityScenario<WebViewActivity> =
        ActivityScenario.launch(
            Intent(context, WebViewActivity::class.java).apply {
                if (url != null) putExtra(WebViewActivity.EXTRA_WEB_URL, url)
            }
        )

    private fun <T> ActivityScenario<WebViewActivity>.on(block: (WebViewActivity) -> T): T {
        var result: T? = null
        onActivity { result = block(it) }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun ActivityScenario<WebViewActivity>.webView(): WebView = on {
        checkNotNull(it.window.decorView.findWebView())
    }

    private fun View.findWebView(): WebView? =
        when (this) {
            is WebView -> this
            is ViewGroup ->
                (0 until childCount).firstNotNullOfOrNull { getChildAt(it).findWebView() }
            else -> null
        }

    private fun loadingMessage() = compose.onNodeWithText("Loading webpage", substring = true)

    @Test
    fun `without a URL the screen closes`() {
        launch(url = null).use { scenario ->
            assertEquals(Lifecycle.State.DESTROYED, scenario.state)
        }
    }

    @Test
    fun `the page is loaded behind a loading message that names it`() {
        launch().use { scenario ->
            assertEquals(PAGE, shadowOf(scenario.webView()).lastLoadedUrl)
            loadingMessage().assertTextContains(PAGE, substring = true)
        }
    }

    @Test
    fun `the page shows once it has loaded`() {
        launch().use { scenario ->
            val webView = scenario.webView()

            shadowOf(webView).webViewClient.onPageFinished(webView, PAGE)

            loadingMessage().assertDoesNotExist()
        }
    }

    @Test
    fun `an invalid certificate asks first, then continues`() {
        launch().use { scenario ->
            val handler = sslError(scenario)

            compose
                .onNodeWithText("invalid certificate", substring = true)
                .assertTextContains(PAGE, substring = true)
            assertFalse(handler.wasProceedCalled())

            compose.onNodeWithText("Continue").performClick()

            assertTrue(handler.wasProceedCalled())
            compose.onNodeWithText("Security Notice").assertDoesNotExist()
        }
    }

    @Test
    fun `once accepted, later certificate errors on the page do not ask again`() {
        launch().use { scenario ->
            sslError(scenario)
            compose.onNodeWithText("Continue").performClick()

            val second = sslError(scenario)

            assertTrue(second.wasProceedCalled())
            compose.onNodeWithText("Security Notice").assertDoesNotExist()
        }
    }

    @Test
    fun `an accepted certificate is not asked about again after a recreation`() {
        launch().use { scenario ->
            sslError(scenario)
            compose.onNodeWithText("Continue").performClick()

            scenario.recreate()
            val second = sslError(scenario)

            assertTrue(second.wasProceedCalled())
            compose.onNodeWithText("Security Notice").assertDoesNotExist()
        }
    }

    @Test
    fun `refusing an invalid certificate cancels the load and keeps the screen`() {
        launch().use { scenario ->
            val handler = sslError(scenario)

            compose.onNodeWithText("Cancel").performClick()

            assertTrue(handler.wasCancelCalled())
            scenario.on { assertFalse(it.isFinishing) }
        }
    }

    @Test
    fun `on a TV, refusing an invalid certificate closes the screen`() {
        runOnTv()
        launch().use { scenario ->
            sslError(scenario)

            compose.onNodeWithText("Cancel").performClick()

            scenario.on { assertTrue(it.isFinishing) }
        }
    }

    @Test
    fun `open in browser hands the page to the browser and closes`() {
        launch().use { scenario ->
            compose.onNodeWithContentDescription("Open in browser").performClick()

            val opened = shadowOf(context as Application).nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, opened.action)
            assertEquals(Uri.parse(PAGE), opened.data)
            scenario.on { assertTrue(it.isFinishing) }
        }
    }

    @Test
    fun `there is no browser to open on a TV`() {
        runOnTv()
        launch().use {
            compose.onNodeWithContentDescription("Open in browser").assertDoesNotExist()
        }
    }

    @Test
    fun `back goes back in the page history first, then closes the screen`() {
        launch().use { scenario ->
            val webView = shadowOf(scenario.webView())
            webView.setCanGoBack(true)

            scenario.on { it.onBackPressedDispatcher.onBackPressed() }
            assertEquals(1, webView.goBackInvocations)

            webView.setCanGoBack(false)
            scenario.on { it.onBackPressedDispatcher.onBackPressed() }
            scenario.on { assertTrue(it.isFinishing) }
        }
    }

    private fun runOnTv() {
        shadowOf(context.getSystemService(UiModeManager::class.java))
            .setCurrentModeType(Configuration.UI_MODE_TYPE_TELEVISION)
    }

    /** Reports a certificate error for the page and returns the handler the activity got. */
    private fun sslError(scenario: ActivityScenario<WebViewActivity>): ShadowSslErrorHandler {
        val webView = scenario.webView()
        val handler = Shadow.newInstanceOf(SslErrorHandler::class.java)
        val certificate = HeldCertificate.Builder().commonName("self-signed").build().certificate
        shadowOf(webView)
            .webViewClient
            .onReceivedSslError(
                webView,
                handler,
                SslError(SslError.SSL_UNTRUSTED, SslCertificate(certificate), PAGE),
            )
        shadowOf(Looper.getMainLooper()).idle()
        return Shadow.extract(handler)
    }

    private companion object {
        const val PAGE = "https://data.syncthing.net/"
    }
}
