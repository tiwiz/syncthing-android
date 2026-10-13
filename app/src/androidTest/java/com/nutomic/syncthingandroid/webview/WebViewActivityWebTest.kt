package com.nutomic.syncthingandroid.webview

import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBackUnconditionally
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.web.assertion.WebViewAssertions.webMatches
import androidx.test.espresso.web.sugar.Web.onWebView
import androidx.test.espresso.web.webdriver.DriverAtoms.findElement
import androidx.test.espresso.web.webdriver.DriverAtoms.getText
import androidx.test.espresso.web.webdriver.Locator
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.activities.WebViewActivity
import mockwebserver3.MockWebServer
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [WebViewActivity] with a real WebView, on a page served over HTTPS with a self-signed
 * certificate: the certificate warning, the page itself, links and back navigation.
 */
@RunWith(AndroidJUnit4::class)
class WebViewActivityWebTest {

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
            onView(withText(containsString(url))).inRoot(isDialog()).check(matches(isDisplayed()))
        }
        onView(withText(R.string.cont)).inRoot(isDialog()).perform(click())
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
            onView(withId(R.id.webview)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun refusing_the_certificate_keeps_the_loading_message() {
        launch().use {
            eventually {
                onView(withText(R.string.cancel_title)).inRoot(isDialog()).perform(click())
            }

            onView(withId(R.id.loading_text)).check(matches(withText(containsString(url))))
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun links_open_in_place_and_back_walks_the_history_before_closing() {
        launch().use { scenario ->
            acceptCertificate()
            assertHeading("Usage data")

            // A real tap: Chromium skips history entries left without a user gesture on back,
            // and webClick() clicks through JavaScript.
            onView(withId(R.id.webview)).perform(click())
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
                    |<a id="next" href="/details" style="display:block;height:100vh">Details</a></body></html>
                    """
                        .trimMargin(),
                "/details" to """<html><body><h1 id="heading">Details</h1></body></html>""",
            )
    }
}
