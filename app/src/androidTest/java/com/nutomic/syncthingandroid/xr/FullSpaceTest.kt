package com.nutomic.syncthingandroid.xr

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.activities.MainActivity
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.RunConditionMonitor
import com.nutomic.syncthingandroid.util.ConfigXml
import java.io.File
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The main screen in Full Space on a real Android XR device. Skipped elsewhere, so the CI emulator,
 * which has no XR, runs none of it. Panels cannot be dragged or resized through accessibility, so
 * that still needs a person with a headset.
 */
@RunWith(AndroidJUnit4::class)
class FullSpaceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val ui = SpatialUi()
    private var scenario: ActivityScenario<MainActivity>? = null

    private fun text(id: Int) = context.getString(id)

    @Before
    fun setUp() {
        assumeTrue(
            "Needs an Android XR device",
            context.packageManager.hasSystemFeature(XR_SPATIAL_FEATURE),
        )
        val pkg = context.packageName
        ui.shell("appops set $pkg MANAGE_EXTERNAL_STORAGE allow")
        ui.shell("pm grant $pkg ${Manifest.permission.POST_NOTIFICATIONS}")
        ensureOwnConfig()
        setRunMode(Constants.BTNSTATE_NO_FORCE_START_STOP)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        if (ui.nodeWith(text(R.string.fork_home_space)) == null) {
            ui.click(text(R.string.fork_full_space))
        }
        ui.waitFor(text(R.string.fork_home_space))
    }

    @After
    fun tearDown() {
        if (scenario == null) return // skipped: not an XR device
        // Leave Full Space, so the next launch starts where setUp expects.
        if (ui.nodeWith(text(R.string.fork_home_space)) != null) {
            ui.click(text(R.string.fork_home_space))
            ui.waitFor(text(R.string.fork_full_space))
        }
        scenario?.close()
        setRunMode(Constants.BTNSTATE_NO_FORCE_START_STOP)
    }

    private fun runMode(): Int =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getInt(
                Constants.PREF_BTNSTATE_FORCE_START_STOP,
                Constants.BTNSTATE_NO_FORCE_START_STOP,
            )

    /** Sets the run mode and tells the service, as the app does. */
    private fun setRunMode(mode: Int) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putInt(Constants.PREF_BTNSTATE_FORCE_START_STOP, mode)
            .commit()
        LocalBroadcastManager.getInstance(context)
            .sendBroadcast(Intent(RunConditionMonitor.ACTION_UPDATE_SHOULDRUN_DECISION))
    }

    /**
     * A config made by this device's Syncthing. Other instrumented tests leave a fake one behind,
     * pointing at a mock server, so this class makes its own once. What was there, maybe a real
     * setup with its folders and devices, is put aside and comes back in [restoreConfig].
     */
    private fun ensureOwnConfig() {
        if (configMade) return
        configFiles(context).forEach { file ->
            val backup = backupOf(file)
            backup.delete()
            if (file.exists()) check(file.renameTo(backup)) { "Cannot put $file aside" }
        }
        ConfigXml(context).generateConfig()
        configMade = true
    }

    @Test
    fun the_list_the_rail_the_top_bar_and_the_run_mode_each_get_a_window() {
        val railWindow = ui.windowsWith(text(R.string.show_device_id)).single()
        val topBarWindow = ui.windowsWith(text(R.string.fork_home_space)).single()
        val runModeWindow = ui.windowsWith(text(R.string.run_mode_stop)).single()
        val listWindow = ui.windowsWith(text(R.string.fork_choose_folder)).single()

        val ids = listOf(railWindow, topBarWindow, runModeWindow, listWindow).map { it.id }
        assertEquals(ids.toString(), 4, ids.toSet().size)
    }

    @Test
    fun the_run_mode_orbiter_changes_the_run_mode() {
        ui.click(text(R.string.run_mode_run))

        ui.waitUntil("The run mode is Run") { runMode() == Constants.BTNSTATE_FORCE_START }
    }

    @Test
    fun a_rail_tab_switches_the_list() {
        ui.click(text(R.string.devices))

        ui.waitFor(text(R.string.fork_choose_device))
        ui.waitGone(text(R.string.fork_choose_folder))
    }

    @Test
    fun the_add_button_in_the_rail_opens_its_menu() {
        ui.click(text(R.string.add))

        ui.waitFor(text(R.string.add_folder))
        ui.back()
        ui.waitGone(text(R.string.add_folder))
    }

    @Test
    @Ignore(
        "Fails on a Puck: in Full Space androidx.xr.compose 1.0.0-beta02 hands Back to a hidden " +
            "dialog window that only clears an internal flag, so onDismissRequest never runs " +
            "(docs/TECH-DEBT.md)"
    )
    fun the_device_id_dialog_shows_and_back_closes_it() {
        ui.click(text(R.string.show_device_id))

        ui.waitFor(text(R.string.copy))
        val dialogWindow = ui.windowsWith(text(R.string.copy)).single()
        val railWindow = ui.windowsWith(text(R.string.show_device_id)).single()
        assertNotEquals(railWindow.id, dialogWindow.id)

        ui.back()
        ui.waitGone(text(R.string.copy))
    }

    @Test
    fun the_home_space_button_leaves_full_space() {
        ui.click(text(R.string.fork_home_space))

        ui.waitFor(text(R.string.fork_full_space))
        ui.waitGone(text(R.string.run_mode_stop))
    }

    companion object {
        private const val XR_SPATIAL_FEATURE = "android.software.xr.api.spatial"

        /** The config is made once per run of this class. */
        private var configMade = false

        private fun configFiles(context: Context): List<File> =
            listOf(
                Constants.getConfigFile(context),
                Constants.getHttpsCertFile(context),
                Constants.getHttpsKeyFile(context),
            )

        private fun backupOf(file: File) = File(file.parentFile, file.name + ".before-test")

        /** Puts back the config that was there before this class made its own. */
        @JvmStatic
        @AfterClass
        fun restoreConfig() {
            if (!configMade) return
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            configFiles(context).forEach { file ->
                file.delete()
                val backup = backupOf(file)
                if (backup.exists()) check(backup.renameTo(file)) { "Cannot restore $file" }
            }
            configMade = false
        }
    }
}
