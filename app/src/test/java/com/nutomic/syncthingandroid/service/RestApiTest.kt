package com.nutomic.syncthingandroid.service

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.nutomic.syncthingandroid.activities.ShareActivity
import com.nutomic.syncthingandroid.appDependencies
import com.nutomic.syncthingandroid.model.Event
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.testutil.FakeSyncthing
import com.nutomic.syncthingandroid.testutil.Fixtures
import com.nutomic.syncthingandroid.testutil.Golden
import com.nutomic.syncthingandroid.testutil.ShadowContentResolverWithStatusListeners
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import com.nutomic.syncthingandroid.testutil.ShadowUtilDeviceShell
import com.nutomic.syncthingandroid.testutil.StoppedSyncthingService
import com.nutomic.syncthingandroid.testutil.awaitMain
import com.nutomic.syncthingandroid.testutil.drainMain
import java.io.File
import java.net.URL
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Characterizes [RestApi] against [FakeSyncthing]: what it asks Syncthing for, what it sends back,
 * and what it caches. The shell shadow keeps `shutdown()` from looking for host processes.
 */
@RunWith(AndroidJUnit4::class)
@Config(
    qualifiers = "en-rUS",
    shadows =
        [
            ShadowEnvironmentWithAllFilesAccess::class,
            ShadowUtilDeviceShell::class,
            ShadowContentResolverWithStatusListeners::class,
        ],
    instrumentedPackages = ["com.nutomic.syncthingandroid.util"],
)
class RestApiTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private lateinit var service: SyncthingService
    private lateinit var syncthing: FakeSyncthing
    private var apiAvailable = 0
    private var configChanged = 0

    @Before
    fun setUp() {
        service = StoppedSyncthingService.install()
        syncthing = FakeSyncthing(context)
    }

    @After
    fun tearDown() {
        syncthing.close()
        ShadowUtilDeviceShell.syncConflictFindOutput = ""
    }

    private fun newApi(url: URL = syncthing.url): RestApi =
        RestApi(
            service,
            service.appDependencies().notificationHandler(),
            url,
            API_KEY,
            { apiAvailable++ },
            { configChanged++ },
        )

    private fun loadedApi(): RestApi {
        val api = newApi()
        api.readConfigFromRestApi()
        awaitMain { apiAvailable > 0 }
        return api
    }

    private fun postedConfig(index: Int = -1): com.google.gson.JsonObject {
        val posts = syncthing.requests("/rest/system/config", "POST")
        val post = if (index < 0) posts[posts.size + index] else posts[index]
        return JsonParser.parseString(checkNotNull(post.body).utf8()).asJsonObject
    }

    @Test
    fun `startup loads version, config and status, then reports the API available`() {
        val api = loadedApi()

        assertEquals(1, apiAvailable)
        assertTrue(api.isConfigLoaded)
        assertEquals("unknown-dev", api.version)
        assertEquals(LOCAL_ID, api.localDevice.deviceID)
        assertEquals(listOf(LOCAL_ID, LAPTOP_ID), api.getDevices(true).map { it.deviceID })
        assertEquals(listOf(LAPTOP_ID), api.getDevices(false).map { it.deviceID })
        assertEquals(listOf("cam-photos"), api.folders.map { it.id })
        assertEquals("fixture-api-key", api.gui.apiKey)
    }

    @Test
    fun `startup requests, in any order, all with the API key`() {
        loadedApi()
        // The follow-up queries (pending, completion, connections, stats) are fired after the
        // config arrives; wait for the last of them.
        awaitMain { syncthing.requests("/rest/stats/device").isNotEmpty() }
        awaitMain { syncthing.requests("/rest/system/loglevels").isNotEmpty() }
        drainMain()

        val lines = syncthing.requests.map { "${it.method} ${it.target}" }.sorted()
        Golden.assertMatches("restapi/startup-requests.txt", lines.joinToString("\n"))
        assertTrue(syncthing.requests.all { it.headers["X-API-Key"] == API_KEY })
    }

    @Test
    fun `a GUI listening on all interfaces is still reached on loopback`() {
        val api = newApi(URL("https://0.0.0.0:${syncthing.url.port}"))
        api.readConfigFromRestApi()
        awaitMain { apiAvailable > 0 }

        assertEquals(1, apiAvailable)
    }

    @Test
    fun `a failed startup request is retried until the API is available`() {
        val looper = shadowOf(Looper.getMainLooper())
        syncthing.failNext("GET", "/rest/system/version")
        syncthing.failNext("GET", "/rest/system/config")
        syncthing.failNext("GET", "/rest/system/status", times = 2)
        val api = newApi()

        api.readConfigFromRestApi()
        awaitMain {
            syncthing.requests("/rest/system/status").size == 1 && api.isConfigLoaded.not()
        }
        drainMain()
        assertEquals(0, apiAvailable)

        looper.idleFor(Duration.ofSeconds(2))
        awaitMain { syncthing.requests("/rest/system/status").size == 2 }
        drainMain()
        assertEquals(0, apiAvailable)

        looper.idleFor(Duration.ofSeconds(2))
        awaitMain { apiAvailable > 0 }

        assertEquals(1, apiAvailable)
        assertEquals(2, syncthing.requests("/rest/system/version").size)
        assertEquals(2, syncthing.requests("/rest/system/config", "GET").size)
        assertEquals(3, syncthing.requests("/rest/system/status").size)
    }

    @Test
    fun `startup retries stop after shutdown`() {
        val looper = shadowOf(Looper.getMainLooper())
        syncthing.respond("GET", "/rest/system/status", "boom", code = 500)
        val api = newApi()

        api.readConfigFromRestApi()
        awaitMain { syncthing.requests("/rest/system/status").size == 1 }
        drainMain()
        api.shutdown()
        looper.idleFor(Duration.ofSeconds(10))
        drainMain()

        assertEquals(1, syncthing.requests("/rest/system/status").size)
        assertEquals(0, apiAvailable)
    }

    @Test
    fun `a server certificate other than the pinned one is rejected`() {
        Constants.getHttpsCertFile(context).writeBytes(Fixtures.bytes("certs/rsa-cert.pem"))
        var errors = 0

        newApi().getEvents(0, 0, eventListener(onError = { errors++ }))
        awaitMain { errors > 0 }

        assertTrue(syncthing.requests.isEmpty())
    }

    @Test
    fun `a pending device in the config reload shows a consent notification`() {
        syncthing.respond(
            "GET",
            "/rest/cluster/pending/devices",
            """{"$NEW_ID": {"time": "2026-10-13T10:00:00Z", "name": "tablet", "address": "192.0.2.9:22000"}}""",
        )

        val manager = context.getSystemService(NotificationManager::class.java)

        loadedApi()
        awaitMain { shadowOf(manager).allNotifications.isNotEmpty() }

        val notification = shadowOf(manager).allNotifications.single()
        assertEquals(
            "Expressive Syncthing",
            notification.extras.getString(NotificationCompat.EXTRA_TITLE),
        )
        assertEquals(
            "Device \"tablet\" wants to connect",
            notification.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString(),
        )
    }

    @Test
    fun `adding a folder posts the whole config and follows the configured GUI address`() {
        val api = loadedApi()
        val folder =
            Folder().apply {
                id = "new-folder"
                label = "New"
                path = "/storage/emulated/0/New"
            }

        api.addFolder(folder)
        awaitMain { syncthing.requests("/rest/system/config", "POST").isNotEmpty() }

        val folders =
            postedConfig().getAsJsonArray("folders").map { it.asJsonObject["id"].asString }
        assertEquals(listOf("cam-photos", "new-folder"), folders)
        assertEquals(1, configChanged)
        assertEquals(syncthing.url, api.url)
        assertEquals(listOf("cam-photos", "new-folder"), api.folders.map { it.id })
    }

    @Test
    fun `removing a folder posts the config without it and forgets its share subdirectory`() {
        val api = loadedApi()
        prefs
            .edit()
            .putString(ShareActivity.PREF_FOLDER_SAVED_SUBDIRECTORY + "cam-photos", "x")
            .apply()

        api.removeFolder("cam-photos")
        awaitMain { syncthing.requests("/rest/system/config", "POST").isNotEmpty() }

        assertEquals(0, postedConfig().getAsJsonArray("folders").size())
        assertFalse(prefs.contains(ShareActivity.PREF_FOLDER_SAVED_SUBDIRECTORY + "cam-photos"))
    }

    @Test
    fun `updating and removing a device post the changed device list`() {
        val api = loadedApi()
        val laptop = api.getDevices(false).single().apply { name = "renamed" }

        api.updateDevice(laptop)
        api.removeDevice(LOCAL_ID)
        awaitMain { syncthing.requests("/rest/system/config", "POST").size == 2 }

        // updateDevice removes the device and appends the new version at the end.
        val afterUpdate =
            postedConfig(0).getAsJsonArray("devices").map {
                it.asJsonObject["deviceID"].asString to it.asJsonObject["name"].asString
            }
        assertEquals(listOf(LOCAL_ID to "android-phone", LAPTOP_ID to "renamed"), afterUpdate)
        val afterRemove =
            postedConfig(1).getAsJsonArray("devices").map { it.asJsonObject["deviceID"].asString }
        assertEquals(listOf(LAPTOP_ID), afterRemove)
        assertEquals(2, configChanged)
    }

    @Test
    fun `sending an unchanged config posts back every field Syncthing served`() {
        val api = loadedApi()

        api.sendConfig()
        awaitMain { syncthing.requests("/rest/system/config", "POST").isNotEmpty() }

        // The models also post a few config.xml names Syncthing's JSON ignores, like "stunServer".
        val served = JsonParser.parseString(syncthing.configJson())
        assertEquals(served, onlyKeysOf(served, postedConfig()))
    }

    @Test
    fun `settings the app does not model survive edits of folders, devices and options`() {
        val api = loadedApi()
        val folder = checkNotNull(api.getFolderByID("cam-photos")).apply { label = "Renamed" }
        val laptop = api.getDevices(false).single().apply { name = "renamed" }

        api.updateFolder(folder)
        api.updateDevice(laptop)
        api.editSettings(api.gui, api.options.apply { natEnabled = false })
        api.sendConfig()
        awaitMain { syncthing.requests("/rest/system/config", "POST").size == 3 }

        val served = JsonParser.parseString(syncthing.configJson()).asJsonObject
        val posted = postedConfig()
        assertEquals(served["ldap"], posted["ldap"])
        for (key in listOf("stunServers", "unackedNotificationIDs", "connectionPriorityTcpLan")) {
            assertEquals(
                key,
                served["options"].asJsonObject[key],
                posted["options"].asJsonObject[key],
            )
        }
        assertFalse(posted["options"].asJsonObject["natEnabled"].asBoolean)
        assertEquals(served["gui"].asJsonObject["authMode"], posted["gui"].asJsonObject["authMode"])
        val postedFolder = posted.getAsJsonArray("folders").single().asJsonObject
        assertEquals("Renamed", postedFolder["label"].asString)
        val servedFolder = served.getAsJsonArray("folders").single().asJsonObject
        assertEquals(servedFolder["fsWatcherTimeoutS"], postedFolder["fsWatcherTimeoutS"])
        assertEquals(servedFolder["xattrFilter"], postedFolder["xattrFilter"])
        val servedLaptop =
            served
                .getAsJsonArray("devices")
                .map { it.asJsonObject }
                .single { it["deviceID"].asString == LAPTOP_ID }
        val postedLaptop =
            posted
                .getAsJsonArray("devices")
                .map { it.asJsonObject }
                .single { it["deviceID"].asString == LAPTOP_ID }
        assertEquals("renamed", postedLaptop["name"].asString)
        assertEquals(servedLaptop["maxRequestKiB"], postedLaptop["maxRequestKiB"])
    }

    @Test
    fun `versioning parameters the app removed are not posted back`() {
        val api = loadedApi()
        val staggered =
            checkNotNull(api.getFolderByID("cam-photos")).apply {
                versioning =
                    Folder.Versioning().apply {
                        type = "staggered"
                        params = mapOf("maxAge" to "86400", "cleanInterval" to "3600")
                    }
            }
        api.updateFolder(staggered)
        val simple =
            checkNotNull(api.getFolderByID("cam-photos")).apply {
                versioning =
                    Folder.Versioning().apply {
                        type = "simple"
                        params = mapOf("keep" to "5")
                    }
            }

        api.updateFolder(simple)
        awaitMain { syncthing.requests("/rest/system/config", "POST").size == 2 }

        val versioning =
            postedConfig()
                .getAsJsonArray("folders")
                .single()
                .asJsonObject["versioning"]
                .asJsonObject
        assertEquals("simple", versioning["type"].asString)
        assertEquals(setOf("keep"), versioning["params"].asJsonObject.keySet())
    }

    @Test
    fun `ignoring a device records it once`() {
        val api = loadedApi()

        api.ignoreDevice(NEW_ID, "tablet", "192.0.2.9:22000")
        api.ignoreDevice(NEW_ID, "tablet", "192.0.2.9:22000")
        awaitMain { syncthing.requests("/rest/system/config", "POST").isNotEmpty() }
        drainMain()

        assertEquals(1, syncthing.requests("/rest/system/config", "POST").size)
        val ignored = postedConfig().getAsJsonArray("remoteIgnoredDevices").single().asJsonObject
        assertEquals(NEW_ID, ignored["deviceID"].asString)
        assertEquals("tablet", ignored["name"].asString)
        assertEquals("192.0.2.9:22000", ignored["address"].asString)
    }

    @Test
    fun `ignoring a folder records it on the offering device, unknown devices are skipped`() {
        val api = loadedApi()

        api.ignoreFolder(NEW_ID, "other", "Other")
        api.ignoreFolder(LAPTOP_ID, "music", "Music")
        awaitMain { syncthing.requests("/rest/system/config", "POST").isNotEmpty() }
        drainMain()

        assertEquals(1, syncthing.requests("/rest/system/config", "POST").size)
        val laptop =
            postedConfig()
                .getAsJsonArray("devices")
                .map { it.asJsonObject }
                .single { it["deviceID"].asString == LAPTOP_ID }
        val ignored = laptop.getAsJsonArray("ignoredFolders").single().asJsonObject
        assertEquals("music", ignored["id"].asString)
        assertEquals("Music", ignored["label"].asString)
    }

    @Test
    fun `folder actions and shutdown post to their endpoints`() {
        val api = loadedApi()

        api.overrideChanges("cam-photos")
        api.revertLocalChanges("cam-photos")
        api.rescanAll()
        api.postFolderIgnoreList("cam-photos", arrayOf("*.tmp", "!keep"))
        api.shutdown()
        awaitMain { syncthing.requests("/rest/system/shutdown").isNotEmpty() }
        awaitMain { syncthing.requests.count { it.method == "POST" } == 5 }

        val posts =
            syncthing.requests
                .filter { it.method == "POST" }
                .map { "${it.target} ${it.body?.utf8().orEmpty()}".trim() }
                .sorted()
        assertEquals(
            listOf(
                "/rest/db/ignores?folder=cam-photos {\"ignore\":[\"*.tmp\",\"!keep\"]}",
                "/rest/db/override?folder=cam-photos",
                "/rest/db/revert?folder=cam-photos",
                "/rest/db/scan",
                "/rest/system/shutdown",
            ),
            posts,
        )
    }

    @Test
    fun `events are read since an id, one callback each, then the highest id`() {
        val types = mutableListOf<String>()
        var lastId = -1L

        newApi()
            .getEvents(
                5,
                0,
                eventListener(onEvent = { types += it.type!! }, onDone = { lastId = it }),
            )
        awaitMain { lastId >= 0 }

        val request = syncthing.requests("/rest/events").single()
        assertEquals("5", request.url.queryParameter("since"))
        assertEquals("0", request.url.queryParameter("limit"))
        assertEquals(16, types.size)
        assertEquals("Starting", types.first())
        assertEquals("FolderCompletion", types.last())
        assertEquals(16L, lastId)
    }

    @Test
    fun `an event that does not fit the model is skipped`() {
        syncthing.respond(
            "GET",
            "/rest/events",
            """[{"id": "x", "type": "Ping"}, {"id": 7, "type": "Ping", "data": {}}]""",
        )
        val ids = mutableListOf<Int>()
        var lastId = -1L

        newApi()
            .getEvents(0, 0, eventListener(onEvent = { ids += it.id }, onDone = { lastId = it }))
        awaitMain { lastId >= 0 }

        assertEquals(listOf(7), ids)
        assertEquals(7L, lastId)
    }

    @Test
    fun `a failed event request reports an error`() {
        syncthing.respond("GET", "/rest/events", "boom", code = 500)
        var errors = 0

        newApi().getEvents(0, 0, eventListener(onError = { errors++ }))
        awaitMain { errors > 0 }

        assertEquals(1, errors)
    }

    @Test
    fun `disk events are a short poll, newest first`() {
        syncthing.respond(
            "GET",
            "/rest/events/disk",
            """[{"id": 1, "type": "LocalChangeDetected", "data": {"path": "a.txt"}},
                {"id": 2, "type": "RemoteChangeDetected", "data": {"path": "b.txt"}}]""",
        )
        var ids: List<Long>? = null

        newApi().getDiskEvents(25) { events -> ids = events.map { it.id } }
        awaitMain { ids != null }

        assertEquals(listOf(2L, 1L), ids)
        val request = syncthing.requests("/rest/events/disk").single()
        assertEquals("25", request.url.queryParameter("limit"))
        assertEquals("1", request.url.queryParameter("timeout"))
    }

    @Test
    fun `simple queries parse their responses`() {
        val api = newApi()
        var myId: String? = null
        var version: String? = null
        var discovered: Set<String>? = null
        var ignoreRequested = false
        var report: String? = null
        syncthing.respond("GET", "/rest/svc/report", """{"a":1}""")

        api.getSystemStatus { myId = it.myID }
        api.getSystemVersion { version = it.version }
        api.getDiscoveredDevices { discovered = it.keys }
        api.getFolderIgnoreList("cam-photos") { ignoreRequested = it.ignore == null }
        api.getUsageReport { report = it }
        awaitMain {
            myId != null &&
                version != null &&
                discovered != null &&
                ignoreRequested &&
                report != null
        }

        assertEquals(LOCAL_ID, myId)
        assertEquals("unknown-dev", version)
        assertEquals(
            setOf("B53EEZU-EX76N5D-KIZHSZC-XFUDUBO-LQM5GXS-DB3Z5NH-N5ESLFH-AJ62XAE"),
            discovered,
        )
        assertEquals(
            "cam-photos",
            syncthing.requests("/rest/db/ignores").single().url.queryParameter("folder"),
        )
        assertEquals("{\n  \"a\": 1\n}", report)
    }

    @Test
    fun `the support bundle is written byte for byte`() {
        syncthing.respond("GET", "/rest/debug/support", "zip é")
        val target = File(context.cacheDir, "support.zip")
        var ok: Boolean? = null

        newApi().downloadSupportBundle(target) { ok = it }
        awaitMain { ok != null }

        assertEquals(true, ok)
        assertEquals("zip é".toByteArray(Charsets.UTF_8).toList(), target.readBytes().toList())
    }

    @Test
    fun `startup caches when each device was last seen`() {
        loadedApi()
        val key = Constants.PREF_CACHE_DEVICE_LASTSEEN_PREFIX + LAPTOP_ID

        awaitMain { prefs.contains(key) }

        assertEquals("1970-01-01T01:00:00+01:00", prefs.getString(key, null))
    }

    @Test
    fun `getFolderByID finds a folder or returns null`() {
        val api = loadedApi()

        assertEquals("Camera", api.getFolderByID("cam-photos")?.label)
        assertNull(api.getFolderByID("missing"))
    }

    @Test
    fun `custom sync conditions pause the folders and devices whose conditions are not met`() {
        val api = loadedApi()
        prefs
            .edit()
            .putBoolean(
                Constants.dynPrefObjectCustomSyncConditions("sc_folder_cam-photos"),
                true,
            )
            .putBoolean(
                Constants.dynPrefObjectCustomSyncConditions("sc_device_$LAPTOP_ID"),
                true,
            )
            .commit()
        // No network: neither wi-fi nor mobile data conditions can be met.
        shadowOf(context.getSystemService(ConnectivityManager::class.java))
            .setActiveNetworkInfo(null)

        api.applyCustomRunConditions(
            RunConditionMonitor(context, context.appDependencies().preferences(), {}, {})
        )
        awaitMain { syncthing.requests("/rest/system/config", "POST").isNotEmpty() }

        val config = postedConfig()
        assertTrue(config.getAsJsonArray("folders").single().asJsonObject["paused"].asBoolean)
        val paused =
            config.getAsJsonArray("devices").associate {
                it.asJsonObject["deviceID"].asString to it.asJsonObject["paused"].asBoolean
            }
        assertEquals(mapOf(LOCAL_ID to false, LAPTOP_ID to true), paused)
    }

    @Test
    fun `custom sync conditions change nothing for objects without them`() {
        val api = loadedApi()
        shadowOf(context.getSystemService(ConnectivityManager::class.java))
            .setActiveNetworkInfo(null)

        api.applyCustomRunConditions(
            RunConditionMonitor(context, context.appDependencies().preferences(), {}, {})
        )
        drainMain()

        assertTrue(syncthing.requests("/rest/system/config", "POST").isEmpty())
    }

    @Test
    fun `a folder finishing on a remote device records conflicts and notifies other apps`() {
        val api = loadedApi()
        ShadowUtilDeviceShell.syncConflictFindOutput =
            "IMG_1.sync-conflict-20260101-120000-P56IOI7.jpg\nsub/b.sync-conflict-20260101-120000-P56IOI7.txt"
        api.setRemoteIndexUpdated(LAPTOP_ID, "cam-photos", true)

        api.setRemoteCompletionInfo(LAPTOP_ID, "cam-photos", 0.0, 100.0)
        awaitMain { syncCompleteBroadcasts().isNotEmpty() }

        assertEquals(
            listOf(
                "IMG_1.sync-conflict-20260101-120000-P56IOI7.jpg",
                "sub/b.sync-conflict-20260101-120000-P56IOI7.txt",
            ),
            api.getFolderStatus("cam-photos").value.discoveredConflictFiles.toList(),
        )
        val intent = syncCompleteBroadcasts().single()
        assertEquals("org.decsync.cc", intent.`package`)
        assertEquals(LAPTOP_ID, intent.getStringExtra("deviceId"))
        assertEquals("cam-photos", intent.getStringExtra("folderId"))
        assertEquals("Camera", intent.getStringExtra("folderLabel"))
        assertEquals("/storage/emulated/0/DCIM/Camera", intent.getStringExtra("folderPath"))
        assertEquals("idle", intent.getStringExtra("folderState"))
        // The remote index flag is consumed, so the next 100% does not notify again.
        assertFalse(api.getFolderStatus("cam-photos").value.remoteIndexUpdated)
    }

    @Test
    fun `a folder reaching 100 percent without remote changes notifies nobody`() {
        val api = loadedApi()

        api.setRemoteCompletionInfo(LAPTOP_ID, "cam-photos", 0.0, 100.0)
        drainMain()

        assertTrue(syncCompleteBroadcasts().isEmpty())
    }

    @Test
    fun `the sync complete broadcast uses the application id prefixed action`() {
        val api = loadedApi()
        api.setRemoteIndexUpdated(LAPTOP_ID, "cam-photos", true)

        api.setRemoteCompletionInfo(LAPTOP_ID, "cam-photos", 0.0, 100.0)
        awaitMain { syncCompleteBroadcasts().isNotEmpty() }

        // As documented in
        // wiki/developers/Integrate-your-app-with-Syncthing-receive-folder-sync-status.md
        // (the debug build adds ".debug" to the application id).
        assertEquals(
            "${context.packageName}.ACTION_NOTIFY_FOLDER_SYNC_COMPLETE",
            syncCompleteBroadcasts().single().action,
        )
    }

    /** [posted] without the object keys [served] lacks, at every level. */
    private fun onlyKeysOf(served: JsonElement, posted: JsonElement): JsonElement =
        when {
            served.isJsonObject && posted.isJsonObject ->
                com.google.gson.JsonObject().apply {
                    for ((key, value) in posted.asJsonObject.entrySet()) {
                        served.asJsonObject[key]?.let { add(key, onlyKeysOf(it, value)) }
                    }
                }
            served.isJsonArray &&
                posted.isJsonArray &&
                served.asJsonArray.size() == posted.asJsonArray.size() ->
                com.google.gson.JsonArray().apply {
                    served.asJsonArray.zip(posted.asJsonArray).forEach { (s, p) ->
                        add(onlyKeysOf(s, p))
                    }
                }
            else -> posted
        }

    private fun syncCompleteBroadcasts(): List<Intent> =
        shadowOf(context as Application).broadcastIntents.filter {
            it.action?.endsWith("ACTION_NOTIFY_FOLDER_SYNC_COMPLETE") == true
        }

    private fun eventListener(
        onError: () -> Unit = {},
        onEvent: (Event) -> Unit = {},
        onDone: (Long) -> Unit = {},
    ) =
        object : RestApi.OnReceiveEventListener {
            override fun onError() = onError()

            override fun onEvent(event: Event, json: JsonElement) = onEvent(event)

            override fun onDone(lastId: Long) = onDone(lastId)
        }

    private companion object {
        const val API_KEY = "fixture-api-key"
        const val LOCAL_ID = "MFZWI3D-BONSGYC-YLTMRWG-C43ENR5-QXGZDMM-FZWI3DP-BONSGYY-LTMRWAD"
        const val LAPTOP_ID = "P56IOI7-MZJNU2Y-IQGDREY-DM2MGTI-MGL3BXN-PQ6W5BM-TBBZ4TJ-XZWICQ2"
        const val NEW_ID = "AIR6LPZ-7K4PTTV-UXQSMUU-CPQ5YWH-OEDFIIQ-JUG777G-2YQXXR5-YD6AWQR"
    }
}
