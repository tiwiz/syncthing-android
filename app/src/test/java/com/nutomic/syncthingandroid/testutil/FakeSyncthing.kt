package com.nutomic.syncthingandroid.testutil

import android.content.Context
import android.os.Looper
import com.android.volley.RequestQueue
import com.nutomic.syncthingandroid.http.ApiRequest
import com.nutomic.syncthingandroid.service.Constants
import java.io.Closeable
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.robolectric.Shadows.shadowOf

/**
 * A fake Syncthing REST API: HTTPS on 127.0.0.1, answering with the captured responses in
 * `fixtures/syncthing/`. Its self-signed certificate is written where the app pins the GUI
 * certificate (`Constants.getHttpsCertFile`), like the real Syncthing does on first start.
 *
 * Volley delivers responses on the main looper, so tests wait with [awaitMain].
 */
class FakeSyncthing(context: Context) : Closeable {

    private val certificate =
        HeldCertificate.Builder()
            .commonName("syncthing")
            .addSubjectAlternativeName("127.0.0.1")
            .build()
    private val server = MockWebServer()
    private val overrides = ConcurrentHashMap<String, MockResponse>()
    private val recorded = CopyOnWriteArrayList<RecordedRequest>()

    /** Every request received so far, oldest first. */
    val requests: List<RecordedRequest>
        get() = recorded.toList()

    /** Base URL of the API, as `ConfigXml.getWebGuiUrl` would return it. */
    val url: URL
        get() = URL("https://127.0.0.1:${server.port}")

    init {
        resetVolleyQueue()
        Constants.getHttpsCertFile(context).writeText(certificate.certificatePem())
        server.useHttps(
            HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory()
        )
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    recorded += request
                    val path = request.url.encodedPath
                    return overrides["${request.method} $path"] ?: fixtureResponse(request, path)
                }
            }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    /** Answers `method path` with [body] and [code] instead of the fixture. */
    fun respond(method: String, path: String, body: String, code: Int = 200) {
        overrides["$method $path"] = MockResponse.Builder().code(code).body(body).build()
    }

    /** Requests received for [path], oldest first. */
    fun requests(path: String, method: String? = null): List<RecordedRequest> = requests.filter {
        it.url.encodedPath == path && (method == null || it.method == method)
    }

    /** The config the fake serves, with the GUI address pointing at this server. */
    fun configJson(): String =
        Fixtures.text("syncthing/system_config.json")
            .replace("\"127.0.0.1:8384\"", "\"127.0.0.1:${server.port}\"")

    override fun close() {
        server.close()
        resetVolleyQueue()
    }

    private fun fixtureResponse(request: RecordedRequest, path: String): MockResponse {
        if (request.method == "POST") return json("")
        val fixture =
            when (path) {
                "/rest/system/config" -> return json(configJson())
                else -> FIXTURES[path]
            }
        return fixture?.let { json(Fixtures.text("syncthing/$it")) }
            ?: MockResponse.Builder().code(NOT_FOUND).body("not found").build()
    }

    private fun json(body: String): MockResponse =
        MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()

    companion object {
        private const val NOT_FOUND = 404

        private val FIXTURES =
            mapOf(
                "/rest/cluster/pending/devices" to "cluster_pending_devices.json",
                "/rest/cluster/pending/folders" to "cluster_pending_folders.json",
                "/rest/db/completion" to "db_completion.json",
                "/rest/db/ignores" to "db_ignores.json",
                "/rest/db/status" to "db_status.json",
                "/rest/events" to "events.json",
                "/rest/events/disk" to "events_disk.json",
                "/rest/stats/device" to "stats_device.json",
                "/rest/system/connections" to "system_connections.json",
                "/rest/system/discovery" to "system_discovery.json",
                "/rest/system/status" to "system_status.json",
                "/rest/system/version" to "system_version.json",
            )

        /**
         * `ApiRequest` keeps one Volley queue for the whole process, bound to the files dir of the
         * first test that used it. Each test gets a fresh one.
         */
        private fun resetVolleyQueue() {
            val field = ApiRequest::class.java.getDeclaredField("sVolleyQueue")
            field.isAccessible = true
            (field.get(null) as RequestQueue?)?.stop()
            field.set(null, null)
        }
    }
}

/** Runs the main looper until [condition] holds, so Volley can deliver its responses. */
fun awaitMain(
    timeoutMillis: Long = 5_000,
    describe: () -> String = { "" },
    condition: () -> Boolean,
) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (!condition()) {
        check(System.currentTimeMillis() < deadline) {
            "Timed out waiting on the main looper. ${describe()}".trim()
        }
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(POLL_MILLIS)
    }
}

/**
 * Runs the main looper for [millis], for checks that something did *not* happen. Wait for the
 * request that matters first, then call this so its response has time to arrive.
 */
fun drainMain(millis: Long = 300) {
    val end = System.currentTimeMillis() + millis
    while (System.currentTimeMillis() < end) {
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(POLL_MILLIS)
    }
}

private const val POLL_MILLIS = 10L
