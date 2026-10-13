package com.nutomic.syncthingandroid.http

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.volley.VolleyError
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.testutil.FakeSyncthing
import com.nutomic.syncthingandroid.testutil.awaitMain
import com.nutomic.syncthingandroid.testutil.drainMain
import java.net.InetAddress
import java.net.URL
import java.security.cert.CertificateException
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The Volley requests to the local Syncthing: loopback pinning, the API key, query strings and
 * bodies, response charsets, errors, polling for the web GUI, and the pinned-certificate trust.
 */
@RunWith(AndroidJUnit4::class)
class ApiRequestTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val certificate =
        HeldCertificate.Builder()
            .commonName("syncthing")
            .addSubjectAlternativeName("127.0.0.1")
            .build()
    private val server = MockWebServer()
    private val answers = ArrayDeque<MockResponse>()

    @Before
    fun setUp() {
        FakeSyncthing.resetVolleyQueue()
        Constants.getHttpsCertFile(context).writeText(certificate.certificatePem())
        server.useHttps(
            HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory()
        )
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    synchronized(answers) { answers.removeFirstOrNull() } ?: ok("{}")
            }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun tearDown() {
        server.close()
        FakeSyncthing.resetVolleyQueue()
    }

    private fun answer(vararg responses: MockResponse) =
        synchronized(answers) { answers.addAll(responses) }

    private fun ok(body: String, contentType: String? = null): MockResponse =
        MockResponse.Builder()
            .apply { if (contentType != null) addHeader("Content-Type", contentType) }
            .body(body)
            .build()

    private fun utf8(body: String, contentType: String): MockResponse =
        MockResponse.Builder()
            .addHeader("Content-Type", contentType)
            .body(Buffer().writeUtf8(body))
            .build()

    /** The GUI address as the config has it with remote access on: all interfaces. */
    private val allInterfaces: URL
        get() = URL("https://0.0.0.0:${server.port}")

    private fun get(path: String, params: Map<String, String>? = null): String {
        var result: String? = null
        GetRequest(context, allInterfaces, path, API_KEY, params, { result = it }, {})
        awaitMain { result != null }
        return checkNotNull(result)
    }

    @Test
    fun `requests go to loopback with the API key and the query`() {
        assertEquals("{}", get(GetRequest.URI_DB_STATUS, mapOf("folder" to "cam photos")))

        val request = server.takeRequest()
        assertEquals("127.0.0.1", request.url.host)
        assertEquals("GET", request.method)
        assertEquals("/rest/db/status", request.url.encodedPath)
        assertEquals("cam photos", request.url.queryParameter("folder"))
        assertEquals(API_KEY, request.headers["X-API-Key"])
    }

    @Test
    fun `a JSON answer without a charset is read as UTF-8`() {
        answer(utf8("\"café\"", "application/json"))

        assertEquals("\"café\"", get(GetRequest.URI_CONFIG))
    }

    @Test
    fun `an explicit charset wins`() {
        answer(utf8("café", "text/plain; charset=utf-8"))

        assertEquals("café", get(GetRequest.URI_CONFIG))
    }

    @Test
    fun `other answers without a charset are read as ISO-8859-1, like Volley`() {
        answer(utf8("café", "text/plain"))

        assertEquals("cafÃ©", get(GetRequest.URI_CONFIG))
    }

    @Test
    fun `errors reach the error listener with the status code`() {
        answer(MockResponse.Builder().code(SERVER_ERROR).body("boom").build())
        var error: VolleyError? = null

        GetRequest(context, allInterfaces, GetRequest.URI_CONFIG, API_KEY, null, {}, { error = it })
        awaitMain { error != null }

        assertEquals(SERVER_ERROR, error?.networkResponse?.statusCode)
    }

    @Test
    fun `a post sends its body and query`() {
        var result: String? = null

        PostRequest(
            context,
            allInterfaces,
            PostRequest.URI_DB_SCAN,
            API_KEY,
            mapOf("folder" to "cam-photos"),
            "{\"a\":1}",
        ) {
            result = it
        }
        awaitMain { result != null }

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/rest/db/scan", request.url.encodedPath)
        assertEquals("cam-photos", request.url.queryParameter("folder"))
        assertEquals("{\"a\":1}", request.body?.utf8())
        assertEquals(API_KEY, request.headers["X-API-Key"])
    }

    @Test
    fun `a post without a body or listener still goes out`() {
        PostRequest(
            context,
            allInterfaces,
            PostRequest.URI_SYSTEM_SHUTDOWN,
            API_KEY,
            null,
            null,
            null,
        )

        val request =
            checkNotNull(server.takeRequest(TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals("/rest/system/shutdown", request.url.encodedPath)
        assertEquals(0L, request.bodySize)
    }

    @Test
    fun `the web GUI is polled until it answers`() {
        answer(
            MockResponse.Builder().code(SERVER_ERROR).build(),
            MockResponse.Builder().code(SERVER_ERROR).build(),
            ok("<html></html>"),
        )
        val calls = AtomicInteger()
        var page: String? = null

        PollWebGuiAvailableTask(context, allInterfaces, API_KEY) {
            calls.incrementAndGet()
            page = it
        }
        awaitWithClock { page != null }
        drainMain()

        assertEquals("<html></html>", page)
        assertEquals(1, calls.get())
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `a cancelled poll neither calls back nor polls again`() {
        answer(MockResponse.Builder().code(SERVER_ERROR).build())
        var called = false

        val task = PollWebGuiAvailableTask(context, allInterfaces, API_KEY) { called = true }
        awaitWithClock { server.requestCount == 1 }
        task.cancelRequestsAndCallback()
        repeat(POLL_ROUNDS) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(POLL_MILLIS))
        }
        drainMain()

        assertEquals(false, called)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `the trust manager accepts the pinned certificate only`() {
        val trustManager = SyncthingTrustManager(Constants.getHttpsCertFile(context))
        val stranger = HeldCertificate.Builder().commonName("mitm").build().certificate

        trustManager.checkServerTrusted(arrayOf(certificate.certificate), "RSA")
        assertThrows(CertificateException::class.java) {
            trustManager.checkServerTrusted(arrayOf(stranger), "RSA")
        }
        assertNull(trustManager.acceptedIssuers)
    }

    @Test
    fun `without the pinned certificate nothing is trusted`() {
        val trustManager = SyncthingTrustManager(context.filesDir.resolve("missing.pem"))

        assertThrows(CertificateException::class.java) {
            trustManager.checkServerTrusted(arrayOf(certificate.certificate), "RSA")
        }
    }

    /** Like [awaitMain], but lets the main looper's clock run so delayed polls happen. */
    private fun awaitWithClock(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out" }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(POLL_MILLIS))
            Thread.sleep(POLL_MILLIS)
        }
    }

    private companion object {
        const val API_KEY = "test-api-key"
        const val SERVER_ERROR = 500
        const val TIMEOUT_SECONDS = 5L
        const val POLL_MILLIS = 50L
        const val POLL_ROUNDS = 10
    }
}
