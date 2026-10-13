package com.nutomic.syncthingandroid.webview

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import androidx.compose.ui.test.hasTextExactly
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import androidx.test.espresso.web.assertion.WebViewAssertions.webMatches
import androidx.test.espresso.web.sugar.Web.onWebView
import androidx.test.espresso.web.webdriver.DriverAtoms.findElement
import androidx.test.espresso.web.webdriver.DriverAtoms.getText
import androidx.test.espresso.web.webdriver.DriverAtoms.webClick
import androidx.test.espresso.web.webdriver.Locator
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.service.SyncthingServiceBinder
import com.nutomic.syncthingandroid.webgui.WebGuiActivity
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import mockwebserver3.MockWebServer
import okhttp3.tls.HeldCertificate
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.equalTo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [WebGuiActivity] with a real WebView against a fake Syncthing GUI served over HTTPS with the
 * certificate the activity pins to. Syncthing itself is kept from starting (force stop), and the
 * test moves the service to ACTIVE, as Syncthing would once its API answers.
 */
@RunWith(AndroidJUnit4::class)
class WebGuiActivityWebTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val certificate = selfSignedLoopbackCertificate()
    private var server: MockWebServer? = null
    private var connection: ServiceConnection? = null

    @Before
    fun setUp() {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putInt(Constants.PREF_BTNSTATE_FORCE_START_STOP, Constants.BTNSTATE_FORCE_STOP)
            .commit()
        Intents.init()
    }

    @After
    fun tearDown() {
        Intents.release()
        connection?.let(context::unbindService)
        server?.close()
    }

    /** Points the GUI address in config.xml at [port] and trusts [pinned] as Syncthing's key. */
    private fun installSyncthingFiles(port: Int, pinned: HeldCertificate = certificate) {
        val config =
            checkNotNull(javaClass.classLoader?.getResourceAsStream("syncthing/config.xml"))
                .bufferedReader()
                .use { it.readText() }
                .replace("<address>127.0.0.1:8384</address>", "<address>0.0.0.0:$port</address>")
        Constants.getConfigFile(context).writeText(config)
        Constants.getHttpsCertFile(context).writeText(pinned.certificatePem())
    }

    private fun startGui(): MockWebServer =
        startPageServer(certificate, mapOf("/" to GUI_PAGE)).also {
            server = it
            installSyncthingFiles(it.port)
        }

    /** Binds to the app's service and reports it ACTIVE to its listeners. */
    private fun reportSyncthingActive() {
        val bound = CountDownLatch(1)
        lateinit var service: SyncthingService
        val serviceConnection =
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    service = (binder as SyncthingServiceBinder).service
                    bound.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName) = Unit
            }
        connection = serviceConnection
        context.bindService(
            Intent(context, SyncthingService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE,
        )
        assertTrue(bound.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            SyncthingService::class
                .java
                .getDeclaredMethod("onServiceStateChange", SyncthingService.State::class.java)
                .apply { isAccessible = true }
                .invoke(service, SyncthingService.State.ACTIVE)
        }
    }

    private fun assertHeading(text: String) = eventually {
        onWebView()
            .withElement(findElement(Locator.ID, "heading"))
            .check(webMatches(getText(), equalTo(text)))
    }

    @Test
    fun the_GUI_loads_over_the_pinned_certificate_with_the_GUI_credentials() {
        val gui = startGui()
        ActivityScenario.launch(WebGuiActivity::class.java).use {
            compose.onNodeWithText("Waiting for GUI").assertExists()

            reportSyncthingActive()

            assertHeading("Fake Syncthing")
            compose.waitUntil(TIMEOUT_MILLIS) {
                compose
                    .onAllNodes(hasTextExactly("Waiting for GUI"))
                    .fetchSemanticsNodes()
                    .isEmpty()
            }
            val request = checkNotNull(gui.takeRequest(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals("/", request.url.encodedPath)
            assertTrue(request.headers["Authorization"].orEmpty().startsWith("Basic "))
        }
    }

    @Test
    fun outside_links_open_in_another_app() {
        startGui()
        intending(hasAction(Intent.ACTION_VIEW))
            .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
        ActivityScenario.launch(WebGuiActivity::class.java).use {
            reportSyncthingActive()
            assertHeading("Fake Syncthing")

            onWebView().withElement(findElement(Locator.ID, "docs")).perform(webClick())

            eventually { intended(allOf(hasAction(Intent.ACTION_VIEW), hasData(Uri.parse(DOCS)))) }
            assertHeading("Fake Syncthing")
        }
    }

    @Test
    fun a_server_with_another_certificate_is_refused() {
        val gui = startGui()
        installSyncthingFiles(gui.port, pinned = HeldCertificate.Builder().build())
        ActivityScenario.launch(WebGuiActivity::class.java).use {
            reportSyncthingActive()

            compose.waitUntil(TIMEOUT_MILLIS) {
                compose.onAllNodes(hasTextExactly(LOAD_FAILED)).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(0, gui.requestCount)
        }
    }

    @Test
    fun a_GUI_that_does_not_answer_shows_the_error() {
        val closedPort = ServerSocket(0).use { it.localPort }
        installSyncthingFiles(closedPort)
        ActivityScenario.launch(WebGuiActivity::class.java).use {
            reportSyncthingActive()

            compose.waitUntil(TIMEOUT_MILLIS) {
                compose.onAllNodes(hasTextExactly(LOAD_FAILED)).fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 30L
        const val TIMEOUT_MILLIS = 30_000L
        const val DOCS = "https://docs.syncthing.net/"
        const val LOAD_FAILED = "Couldn't load the Web GUI. Please capture a log and report this."
        const val GUI_PAGE =
            """<html><body><h1 id="heading">Fake Syncthing</h1>
            <a id="docs" href="$DOCS">Docs</a></body></html>"""
    }
}
