package com.nutomic.syncthingandroid.service

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nutomic.syncthingandroid.appDependencies
import com.nutomic.syncthingandroid.testutil.FakeSyncthing
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import com.nutomic.syncthingandroid.testutil.ShadowUtilDeviceShell
import com.nutomic.syncthingandroid.testutil.StoppedSyncthingService
import com.nutomic.syncthingandroid.testutil.awaitMain
import com.nutomic.syncthingandroid.testutil.drainMain
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The versioning cleanup [RestApi] forces every tenth start by lowering each folder's cleanup
 * interval for a while, against [FakeSyncthing].
 */
@RunWith(AndroidJUnit4::class)
@Config(
    qualifiers = "en-rUS",
    shadows = [ShadowEnvironmentWithAllFilesAccess::class, ShadowUtilDeviceShell::class],
    instrumentedPackages = ["com.nutomic.syncthingandroid.util"],
)
class RestApiVersioningCleanupTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private lateinit var service: SyncthingService
    private lateinit var syncthing: FakeSyncthing
    private var apiAvailable = 0

    @Before
    fun setUp() {
        service = StoppedSyncthingService.install()
        syncthing = FakeSyncthing(context)
    }

    @After
    fun tearDown() {
        syncthing.close()
    }

    private fun loadedApi(): RestApi {
        val api =
            RestApi(
                service,
                service.appDependencies().notificationHandler(),
                syncthing.url,
                "fixture-api-key",
                { apiAvailable++ },
                {},
            )
        api.readConfigFromRestApi()
        awaitMain { apiAvailable > 0 }
        return api
    }

    private fun postedConfig(index: Int): JsonObject =
        JsonParser.parseString(
                checkNotNull(syncthing.requests("/rest/system/config", "POST")[index].body).utf8()
            )
            .asJsonObject

    /** Serves the config with the folder's versioning set to [type] every [cleanupIntervalS]. */
    private fun serveVersioning(type: String, cleanupIntervalS: Int) {
        syncthing.respond(
            "GET",
            "/rest/system/config",
            syncthing
                .configJson()
                .replaceFirst(
                    "\"type\": \"\",\n        \"params\": {},\n        \"cleanupIntervalS\": 3600",
                    "\"type\": \"$type\",\n        \"params\": {},\n        " +
                        "\"cleanupIntervalS\": $cleanupIntervalS",
                ),
        )
    }

    private fun postedCleanupIntervals(): List<Int> =
        syncthing.requests("/rest/system/config", "POST").indices.map {
            postedConfig(it)
                .getAsJsonArray("folders")
                .single()
                .asJsonObject["versioning"]
                .asJsonObject["cleanupIntervalS"]
                .asInt
        }

    @Test
    fun `every tenth start forces a versioning cleanup, then restores the folder's own interval`() {
        serveVersioning("simple", 86400)
        prefs.edit().putInt(Constants.PREF_APP_START_COUNTER, 9).apply()

        loadedApi()
        awaitMain { syncthing.requests("/rest/system/config", "POST").isNotEmpty() }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        awaitMain { syncthing.requests("/rest/system/config", "POST").size == 2 }

        assertEquals(listOf(2, 86400), postedCleanupIntervals())
        assertEquals(10, prefs.getInt(Constants.PREF_APP_START_COUNTER, 0))
        assertFalse(prefs.contains(Constants.PREF_VERSIONING_CLEANUP_INTERVALS))
    }

    @Test
    fun `a forced cleanup cut short by a shutdown is undone on the next start`() {
        serveVersioning("simple", 86400)
        prefs.edit().putInt(Constants.PREF_APP_START_COUNTER, 9).apply()
        val first = loadedApi()
        awaitMain { syncthing.requests("/rest/system/config", "POST").isNotEmpty() }
        first.shutdown()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        drainMain()
        assertEquals(listOf(2), postedCleanupIntervals())

        // Syncthing kept the forced interval.
        serveVersioning("simple", 2)
        apiAvailable = 0
        loadedApi()
        awaitMain { syncthing.requests("/rest/system/config", "POST").size == 2 }

        assertEquals(listOf(2, 86400), postedCleanupIntervals())
        assertFalse(prefs.contains(Constants.PREF_VERSIONING_CLEANUP_INTERVALS))
    }

    @Test
    fun `the forced cleanup skips folders without versioning or with cleanup turned off`() {
        for ((type, interval) in listOf("" to 3600, "simple" to 0)) {
            serveVersioning(type, interval)
            prefs.edit().putInt(Constants.PREF_APP_START_COUNTER, 9).apply()
            apiAvailable = 0

            loadedApi()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
            drainMain()

            assertTrue(type, syncthing.requests("/rest/system/config", "POST").isEmpty())
        }
    }

    @Test
    fun `other starts leave the versioning cleanup interval alone`() {
        loadedApi()
        drainMain()

        assertTrue(syncthing.requests("/rest/system/config", "POST").isEmpty())
        assertEquals(1, prefs.getInt(Constants.PREF_APP_START_COUNTER, 0))
    }
}
