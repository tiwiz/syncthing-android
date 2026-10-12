package com.nutomic.syncthingandroid.model

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nutomic.syncthingandroid.testutil.ConfigFixture
import com.nutomic.syncthingandroid.testutil.Fixtures
import com.nutomic.syncthingandroid.testutil.Golden
import java.lang.reflect.Type
import org.junit.Test

/**
 * Parses real Syncthing v2.1.7 REST responses into the app's models with a plain [Gson], as
 * `RestApi` does, and pins what the app actually keeps (fields it does not model are dropped).
 */
class RestModelParsingTest {

    private val gson = Gson()

    private fun check(fixture: String, type: Type) {
        val parsed: Any = gson.fromJson(Fixtures.text("syncthing/$fixture.json"), type)
        Golden.assertMatches("rest/$fixture.json", ConfigFixture.gson.toJson(parsed, type))
    }

    private inline fun <reified T> typeOf(): Type = object : TypeToken<T>() {}.type

    @Test fun `system config`() = check("system_config", Config::class.java)

    @Test fun `system status`() = check("system_status", SystemStatus::class.java)

    @Test fun `system version`() = check("system_version", SystemVersion::class.java)

    @Test fun `system connections`() = check("system_connections", Connections::class.java)

    @Test fun `system discovery`() = check("system_discovery", typeOf<Map<String, DiscoveredDevice>>())

    @Test fun `folder status`() = check("db_status", FolderStatus::class.java)

    @Test fun `completion`() = check("db_completion", CompletionInfo::class.java)

    @Test fun `events`() = check("events", typeOf<List<Event>>())

    @Test fun `disk events`() = check("events_disk", typeOf<List<DiskEvent>>())

    @Test fun `device statistics`() = check("stats_device", typeOf<Map<String, DeviceStat>>())

    @Test fun `ignore list`() = check("db_ignores", FolderIgnoreList::class.java)

    @Test
    fun `pending devices and folders`() {
        check("cluster_pending_devices", typeOf<Map<String, PendingDevice>>())
        check("cluster_pending_folders", typeOf<Map<String, PendingFolder>>())
    }
}
