package com.nutomic.syncthingandroid.service

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.LinkProperties
import android.net.RouteInfo
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.testutil.FakeSyncthingBinary
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * Runs [SyncthingRunnable] against [FakeSyncthingBinary]: the command line for each mode, the
 * environment Syncthing gets, and what the app does for each exit code.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-rUS")
class SyncthingRunnableTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app = context as Application
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private lateinit var binary: FakeSyncthingBinary

    @Before
    fun setUp() {
        binary = FakeSyncthingBinary(context)
    }

    private fun run(command: SyncthingRunnable.Command, returnStdOut: Boolean = true): String {
        binary.install()
        return SyncthingRunnable(context, command).run(returnStdOut)
    }

    @Test
    fun `each command runs the binary with its arguments`() {
        val expected =
            mapOf(
                SyncthingRunnable.Command.DEVICE_ID to listOf("device-id"),
                SyncthingRunnable.Command.GENERATE to listOf("generate"),
                SyncthingRunnable.Command.MAIN to listOf("serve", "--no-browser"),
                SyncthingRunnable.Command.RESET_DATABASE to listOf("debug", "reset-database"),
                SyncthingRunnable.Command.RESET_DELTAS to
                    listOf("serve", "--debug-reset-delta-idxs"),
            )
        expected.forEach { (command, args) ->
            run(command)
            assertEquals(command.name, args, binary.args)
        }
    }

    @Test
    fun `the output is returned when asked for`() {
        binary.stdout = "MFZWI3D-BONSGYC-YLTMRWG-C43ENR5-QXGZDMM-FZWI3DP-BONSGYY-LTMRWAD\n"

        assertEquals(
            "MFZWI3D-BONSGYC-YLTMRWG-C43ENR5-QXGZDMM-FZWI3DP-BONSGYY-LTMRWAD\n",
            run(SyncthingRunnable.Command.DEVICE_ID),
        )
    }

    @Test
    fun `the environment points Syncthing at the app's files and disables upgrades`() {
        run(SyncthingRunnable.Command.MAIN)

        val env = binary.env
        assertEquals(context.filesDir.path, env["STHOMEDIR"])
        assertEquals(context.cacheDir.absolutePath, env["SQLITE_TMPDIR"])
        assertEquals("1", env["STNOUPGRADE"])
        assertEquals("1", env["STMONITORED"])
        assertEquals("Syncthing-Fork", env["STVERSIONEXTRA"])
        assertEquals("100", env["GOGC"])
        assertEquals("", env["STTRACE"])
        assertNull(env["all_proxy"])
        assertNull(env["http_proxy"])
    }

    @Test
    fun `trace facilities, proxies and custom variables reach the environment`() {
        prefs
            .edit()
            .putStringSet(Constants.PREF_DEBUG_FACILITIES_ENABLED, setOf("model"))
            .putString(Constants.PREF_SOCKS_PROXY_ADDRESS, "socks5://192.0.2.1:1080")
            .putString(Constants.PREF_HTTP_PROXY_ADDRESS, "http://192.0.2.1:3128")
            .putString(Constants.PREF_ENVIRONMENT_VARIABLES, "STGUIASSETS=/tmp/gui FOO=a=b")
            .commit()

        run(SyncthingRunnable.Command.MAIN)

        val env = binary.env
        assertEquals("model", env["STTRACE"])
        assertEquals("socks5://192.0.2.1:1080", env["all_proxy"])
        assertEquals("http://192.0.2.1:3128", env["http_proxy"])
        assertEquals("http://192.0.2.1:3128", env["https_proxy"])
        assertEquals("/tmp/gui", env["STGUIASSETS"])
        assertEquals("a=b", env["FOO"])
    }

    @Test
    fun `tor replaces the configured proxies`() {
        prefs
            .edit()
            .putBoolean(Constants.PREF_USE_TOR, true)
            .putString(Constants.PREF_HTTP_PROXY_ADDRESS, "http://192.0.2.1:3128")
            .commit()

        run(SyncthingRunnable.Command.MAIN)

        assertEquals("socks5://localhost:9050", binary.env["all_proxy"])
        assertEquals("1", binary.env["ALL_PROXY_NO_FALLBACK"])
        assertNull(binary.env["http_proxy"])
    }

    @Test
    fun `a missing binary is reported`() {
        binary.remove()

        assertThrows(SyncthingRunnable.ExecutableNotFoundException::class.java) {
            SyncthingRunnable(context, SyncthingRunnable.Command.MAIN).run(true)
        }
    }

    @Test
    fun `the serve output goes to the syncthing log`() {
        binary.stdout = "INFO: Starting\n"
        binary.stderr = "WARNING: careful\n"

        run(SyncthingRunnable.Command.MAIN, returnStdOut = false)

        val log = Constants.getSyncthingLogFile(context).readText()
        assertTrue(log, log.contains("INFO: Starting\n"))
        assertTrue(log, log.contains("WARNING: careful\n"))
    }

    @Test
    fun `what the app does for each exit code of the main process`() {
        val outcomes =
            listOf(0, 1, 2, 3, 9, 64, 137, 42).associateWith { code ->
                shadowOf(app).clearStartedServices()
                context.getSystemService(NotificationManager::class.java).cancelAll()
                binary.exitCode = code
                run(SyncthingRunnable.Command.MAIN, returnStdOut = false)

                val started = shadowOf(app).nextStartedService
                val action =
                    when (started?.action) {
                        null -> "nothing"
                        SyncthingService.ACTION_STOP ->
                            "stop (crashed=" +
                                started.getBooleanExtra(
                                    SyncthingService.EXTRA_STOP_AFTER_CRASHED_NATIVE,
                                    false,
                                ) +
                                ")"
                        SyncthingService.ACTION_RESTART -> "restart"
                        else -> started.action
                    }
                val notified =
                    shadowOf(context.getSystemService(NotificationManager::class.java))
                        .allNotifications
                        .map { it.extras.getString(NotificationCompat.EXTRA_TITLE) }
                "$action, notifications=$notified"
            }

        assertEquals(
            mapOf(
                0 to "nothing, notifications=[]",
                1 to "stop (crashed=true), notifications=[Crashed (exit code 1)]",
                2 to "stop (crashed=true), notifications=[Crashed (exit code 2)]",
                3 to "restart, notifications=[]",
                9 to "stop (crashed=true), notifications=[Crashed (exit code 9)]",
                64 to "stop (crashed=true), notifications=[Crashed (exit code 64)]",
                137 to "nothing, notifications=[]",
                42 to "stop (crashed=true), notifications=[Crashed (exit code 42)]",
            ),
            outcomes,
        )
    }

    @Test
    fun `a helper command never stops or restarts the service`() {
        binary.exitCode = 1

        run(SyncthingRunnable.Command.GENERATE, returnStdOut = true)

        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun `the syncthing log is trimmed to its last 200000 lines before a run`() {
        val log = Constants.getSyncthingLogFile(context)
        log.bufferedWriter().use { writer -> (1..200_010).forEach { writer.write("line $it\n") } }

        run(SyncthingRunnable.Command.DEVICE_ID)

        val lines = log.readLines()
        assertEquals(200_000, lines.size)
        assertEquals("line 11", lines.first())
        assertEquals("line 200010", lines.last())
    }

    @Test
    fun `a short syncthing log is kept`() {
        val log = Constants.getSyncthingLogFile(context)
        log.writeText("one\ntwo\n")

        run(SyncthingRunnable.Command.DEVICE_ID)

        assertEquals("one\ntwo\n", log.readText())
    }

    @Test
    fun `the default IPv4 gateway reaches the environment`() {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = checkNotNull(connectivity.activeNetwork)
        val properties =
            LinkProperties().apply {
                interfaceName = "wlan0"
                // RouteInfo's constructors are hidden in the SDK.
                addRoute(
                    ReflectionHelpers.callConstructor(
                        RouteInfo::class.java,
                        ReflectionHelpers.ClassParameter.from(
                            IpPrefix::class.java,
                            IpPrefix(InetAddress.getByName("0.0.0.0"), 0),
                        ),
                        ReflectionHelpers.ClassParameter.from(
                            InetAddress::class.java,
                            InetAddress.getByName("192.0.2.1"),
                        ),
                        ReflectionHelpers.ClassParameter.from(String::class.java, "wlan0"),
                    )
                )
            }
        shadowOf(connectivity).setLinkProperties(network, properties)

        run(SyncthingRunnable.Command.MAIN)

        assertEquals("192.0.2.1", binary.env["FALLBACK_NET_GATEWAY_IPV4"])
        assertEquals("100", binary.env["GOGC"])
    }

    @Test
    fun `without a network there is no gateway`() {
        shadowOf(context.getSystemService(ConnectivityManager::class.java))
            .setDefaultNetworkActive(false)
        shadowOf(context.getSystemService(ConnectivityManager::class.java))
            .setActiveNetworkInfo(null)

        run(SyncthingRunnable.Command.MAIN)

        assertNull(binary.env["FALLBACK_NET_GATEWAY_IPV4"])
    }
}
