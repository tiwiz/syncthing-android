package com.nutomic.syncthingandroid.webview

import android.content.Intent
import android.webkit.WebView
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBackUnconditionally
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.web.assertion.WebViewAssertions.webMatches
import androidx.test.espresso.web.sugar.Web.onWebView
import androidx.test.espresso.web.webdriver.DriverAtoms.findElement
import androidx.test.espresso.web.webdriver.DriverAtoms.getText
import androidx.test.espresso.web.webdriver.Locator
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.activities.WebViewActivity
import mockwebserver3.MockWebServer
import org.hamcrest.Matchers.equalTo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [WebViewActivity] with a real WebView, on a page served over HTTPS with a self-signed
 * certificate: the certificate warning, the page itself, links and back navigation.
 */
@RunWith(AndroidJUnit4::class)
class WebViewActivityWebTest {

    @get:Rule val compose = createEmptyComposeRule()

    private lateinit var server: MockWebServer
    private lateinit var url: String

    @Before
    fun setUp() {
        server = startPageServer(selfSignedLoopbackCertificate(), PAGES)
        url = server.url("/").toString()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun launch(): ActivityScenario<WebViewActivity> =
        ActivityScenario.launch(
            Intent(ApplicationProvider.getApplicationContext(), WebViewActivity::class.java)
                .putExtra(WebViewActivity.EXTRA_WEB_URL, url)
        )

    private fun acceptCertificate() {
        eventually {
            compose
                .onNodeWithText("invalid certificate", substring = true)
                .assertTextContains(url, substring = true)
        }
        compose.onNodeWithText("Continue").performClick()
    }

    private fun assertHeading(text: String) = eventually {
        onWebView()
            .withElement(findElement(Locator.ID, "heading"))
            .check(webMatches(getText(), equalTo(text)))
    }

    @Test
    fun accepting_the_certificate_shows_the_page() {
        launch().use {
            acceptCertificate()

            assertHeading("Usage data")
            onView(isAssignableFrom(WebView::class.java)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun refusing_the_certificate_loads_nothing() {
        launch().use {
            eventually { compose.onNodeWithText("Cancel").performClick() }

            compose.onNodeWithText("Security Notice").assertDoesNotExist()
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun links_open_in_place_and_back_walks_the_history_before_closing() {
        launch().use { scenario ->
            acceptCertificate()
            assertHeading("Usage data")

            // A real tap: Chromium skips history entries left without a user gesture on back,
            // and webClick() clicks through JavaScript. The link covers the whole page, so the tap
            // in the middle of the WebView hits it.
            onView(isAssignableFrom(WebView::class.java)).perform(click())
            assertHeading("Details")
            pressBackUnconditionally()
            assertHeading("Usage data")

            pressBackUnconditionally()
            eventually { assertEquals(Lifecycle.State.DESTROYED, scenario.state) }
        }
    }

    private companion object {
        val PAGES =
            mapOf(
                "/" to
                    """
                    |<html><body><h1 id="heading">Usage data</h1>
                    |<a id="next" href="/details" style="position:fixed;top:0;right:0;bottom:0;left:0">Details</a></body></html>
                    """
                        .trimMargin(),
                "/details" to """<html><body><h1 id="heading">Details</h1></body></html>""",
            )
    }
}
