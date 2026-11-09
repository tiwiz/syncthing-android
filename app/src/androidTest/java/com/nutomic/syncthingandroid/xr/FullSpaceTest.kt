package com.nutomic.syncthingandroid.xr

import android.Manifest
import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.activities.MainActivity
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.util.ConfigXml
import org.junit.After
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
        if (!Constants.getConfigFile(context).exists()) ConfigXml(context).generateConfig()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        ui.click(text(R.string.fork_full_space))
        ui.waitFor(text(R.string.fork_home_space))
    }

    @After
    fun tearDown() {
        scenario?.close()
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .remove(Constants.PREF_BTNSTATE_FORCE_START_STOP)
            .commit()
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

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val deadline = System.currentTimeMillis() + WAIT_MILLIS
        while (
            prefs.getInt(Constants.PREF_BTNSTATE_FORCE_START_STOP, -1) !=
                Constants.BTNSTATE_FORCE_START && System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(STEP_MILLIS)
        }
        assertEquals(
            Constants.BTNSTATE_FORCE_START,
            prefs.getInt(Constants.PREF_BTNSTATE_FORCE_START_STOP, -1),
        )
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

        val copy = ui.waitFor(text(R.string.copy))
        val dialogWindow = ui.windowsWith(text(R.string.copy)).single()
        val railWindow = ui.windowsWith(text(R.string.show_device_id)).single()
        assertNotEquals(railWindow.id, dialogWindow.id)
        copy.recycle()

        ui.back()
        ui.waitGone(text(R.string.copy))
    }

    @Test
    fun the_home_space_button_leaves_full_space() {
        ui.click(text(R.string.fork_home_space))

        ui.waitFor(text(R.string.fork_full_space))
        assertEquals(0, ui.windowsWith(text(R.string.run_mode_stop)).size)
    }

    private companion object {
        const val XR_SPATIAL_FEATURE = "android.software.xr.api.spatial"
        const val WAIT_MILLIS = 15_000L
        const val STEP_MILLIS = 200L
    }
}
