package com.nutomic.syncthingandroid.activities

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.testutil.ConfigFixture
import com.nutomic.syncthingandroid.testutil.ShadowContentResolverWithStatusListeners
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import com.nutomic.syncthingandroid.testutil.StoppedSyncthingService
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The folder type, pull order and sync conditions screens, on their own and through the folder
 * screen that opens them, with Syncthing stopped (changes go to `config.xml`).
 */
@RunWith(AndroidJUnit4::class)
@Config(
    qualifiers = "en-rUS",
    shadows =
        [
            ShadowEnvironmentWithAllFilesAccess::class,
            ShadowContentResolverWithStatusListeners::class,
        ],
)
class FolderDialogsTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val folderDir: File = Files.createTempDirectory("camera").toFile()

    @Before
    fun setUp() {
        System.setProperty("os.version", "6.1.0-android")
        // A folder path the app can write to, so every folder type is offered.
        ConfigFixture.install(context) {
            it.replace("path=\"/storage/emulated/0/DCIM/Camera\"", "path=\"${folderDir.path}\"")
        }
        StoppedSyncthingService.install()
    }

    @After
    fun tearDown() {
        folderDir.deleteRecursively()
    }

    /** The list item with [text], scrolled into view (and so composed) first. */
    private fun scrollTo(text: String): SemanticsNodeInteraction {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
        return compose.onNodeWithText(text)
    }

    private fun <T : Activity> launchDialog(
        activity: Class<T>,
        extra: String,
        value: String,
    ): ActivityScenario<T> =
        ActivityScenario.launchActivityForResult(Intent(context, activity).putExtra(extra, value))

    @Test
    fun `the folder type screen returns the chosen type`() {
        launchDialog(
                FolderTypeDialogActivity::class.java,
                FolderTypeDialogActivity.EXTRA_FOLDER_TYPE,
                Constants.FOLDER_TYPE_SEND_ONLY,
            )
            .use { scenario ->
                compose.onNodeWithText("Send Only").assertIsSelected()
                compose.onNodeWithText("Receive Only").performClick()
                compose.onNodeWithText("Send Only").assertIsNotSelected()
                compose.onNodeWithText("Finish").performClick()

                val result = scenario.result
                assertEquals(Activity.RESULT_OK, result.resultCode)
                assertEquals(
                    Constants.FOLDER_TYPE_RECEIVE_ONLY,
                    result.resultData.getStringExtra(
                        FolderTypeDialogActivity.EXTRA_RESULT_FOLDER_TYPE
                    ),
                )
            }
    }

    @Test
    fun `back on the folder type screen keeps the type`() {
        launchDialog(
                FolderTypeDialogActivity::class.java,
                FolderTypeDialogActivity.EXTRA_FOLDER_TYPE,
                Constants.FOLDER_TYPE_SEND_ONLY,
            )
            .use { scenario ->
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

                assertEquals(
                    Constants.FOLDER_TYPE_SEND_ONLY,
                    scenario.result.resultData.getStringExtra(
                        FolderTypeDialogActivity.EXTRA_RESULT_FOLDER_TYPE
                    ),
                )
            }
    }

    @Test
    fun `the folder type survives recreating the screen`() {
        launchDialog(
                FolderTypeDialogActivity::class.java,
                FolderTypeDialogActivity.EXTRA_FOLDER_TYPE,
                Constants.FOLDER_TYPE_SEND_ONLY,
            )
            .use { scenario ->
                compose.onNodeWithText("Receive Only").performClick()
                scenario.recreate()
                compose.onNodeWithText("Receive Only").assertIsSelected()
                compose.onNodeWithText("Finish").performClick()

                assertEquals(
                    Constants.FOLDER_TYPE_RECEIVE_ONLY,
                    scenario.result.resultData.getStringExtra(
                        FolderTypeDialogActivity.EXTRA_RESULT_FOLDER_TYPE
                    ),
                )
            }
    }

    @Test
    fun `the pull order screen returns the chosen order`() {
        launchDialog(
                PullOrderDialogActivity::class.java,
                PullOrderDialogActivity.EXTRA_PULL_ORDER,
                "random",
            )
            .use { scenario ->
                compose.onNodeWithText("Random").assertIsSelected()
                compose.onNodeWithText("Newest First").performClick()
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

                val result = scenario.result
                assertEquals(Activity.RESULT_OK, result.resultCode)
                assertEquals(
                    "newestFirst",
                    result.resultData.getStringExtra(
                        PullOrderDialogActivity.EXTRA_RESULT_PULL_ORDER
                    ),
                )
            }
    }

    @Test
    fun `sync conditions are saved per folder when leaving the screen`() {
        val prefix = Constants.PREF_OBJECT_PREFIX_FOLDER + "cam-photos"
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putBoolean(Constants.PREF_RUN_ON_MOBILE_DATA, true)
            .commit()

        ActivityScenario.launchActivityForResult<SyncConditionsActivity>(
                SyncConditionsActivity.createIntent(context, prefix, "Camera")
            )
            .use { scenario ->
                compose.onNodeWithText("Run on Wi-Fi").performClick()
                scrollTo("Run on mobile data").assertIsOn()
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

                assertEquals(Activity.RESULT_OK, scenario.result.resultCode)
            }

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        assertFalse(prefs.getBoolean(Constants.dynPrefObjectSyncOnWifi(prefix), true))
        assertTrue(prefs.getBoolean(Constants.dynPrefObjectSyncOnMobileData(prefix), false))
    }

    @Test
    fun `conditions turned off globally cannot be turned on for a folder`() {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putBoolean(Constants.PREF_RUN_ON_WIFI, false)
            .commit()

        ActivityScenario.launch<SyncConditionsActivity>(
                SyncConditionsActivity.createIntent(context, "sc_folder_x", "X")
            )
            .use {
                compose.onNodeWithText("Run on Wi-Fi").assertIsOff().assertIsNotEnabled()
                scrollTo("Run on mobile data").assertIsNotEnabled()
            }
    }

    @Test
    fun `only networks still on the global whitelist can be chosen, and are saved`() {
        val prefix = "sc_folder_x"
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putBoolean(Constants.PREF_USE_WIFI_SSID_WHITELIST, true)
            .putStringSet(Constants.PREF_WIFI_SSID_WHITELIST, setOf("\"home\"", "\"office\""))
            .putStringSet(
                Constants.dynPrefObjectSelectedWhitelistSsid(prefix),
                setOf("\"home\"", "\"gone\""),
            )
            .commit()

        ActivityScenario.launch<SyncConditionsActivity>(
                SyncConditionsActivity.createIntent(context, prefix, "X")
            )
            .use { scenario ->
                compose.onNodeWithText("Run on specified Wi-Fi networks").assertIsOn()
                scrollTo("home").assertIsOn()
                scrollTo("office").assertIsOff().performClick()
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            }

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        assertTrue(prefs.getBoolean(Constants.dynPrefObjectUseWifiSsidWhitelist(prefix), false))
        assertEquals(
            setOf("\"home\"", "\"office\""),
            prefs.getStringSet(Constants.dynPrefObjectSelectedWhitelistSsid(prefix), null),
        )
    }

    @Test
    fun `a global power source is taken over and cannot be changed`() {
        val prefix = "sc_folder_x"
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putString(Constants.PREF_POWER_SOURCE, Constants.PowerSource.CHARGER)
            .putString(
                Constants.dynPrefObjectSyncOnPowerSource(prefix),
                Constants.PowerSource.BATTERY,
            )
            .commit()

        ActivityScenario.launch<SyncConditionsActivity>(
                SyncConditionsActivity.createIntent(context, prefix, "X")
            )
            .use { scenario ->
                scrollTo("AC power.").assertExists()
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            }

        assertEquals(
            Constants.PowerSource.CHARGER,
            PreferenceManager.getDefaultSharedPreferences(context)
                .getString(Constants.dynPrefObjectSyncOnPowerSource(prefix), null),
        )
    }
}
