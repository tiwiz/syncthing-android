package com.nutomic.syncthingandroid.activities

import android.app.Application
import android.content.DialogInterface
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.ListFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.viewpager.widget.ViewPager
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.fragments.DeviceListFragment
import com.nutomic.syncthingandroid.fragments.FolderListFragment
import com.nutomic.syncthingandroid.fragments.StatusFragment
import com.nutomic.syncthingandroid.model.Device
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.views.SegmentedButton
import java.time.Duration
import org.robolectric.Shadows.shadowOf
import org.robolectric.fakes.RoboMenuItem
import org.robolectric.shadows.ShadowDialog

/**
 * Drives the main screen the way a user would, so the tests stay the same whatever the screen is
 * made of.
 */
internal class MainScreen(
    private val scenario: ActivityScenario<MainActivity>,
    @Suppress("unused") private val compose: ComposeTestRule,
) : AutoCloseable {

    enum class Tab {
        FOLDERS,
        DEVICES,
        STATUS,
    }

    /** What the status light in the top bar says. */
    enum class Light {
        ACTIVE,
        STARTING,
        DISABLED,
    }

    /** Runs the main looper, also what the screen scheduled for the next [millis]. */
    fun idle(millis: Long = 0) {
        val looper = shadowOf(Looper.getMainLooper())
        if (millis > 0) looper.idleFor(Duration.ofMillis(millis)) else looper.idle()
    }

    private fun <T> on(block: (MainActivity) -> T): T {
        var result: T? = null
        scenario.onActivity { result = block(it) }
        idle()
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private inline fun <reified F : ListFragment> MainActivity.fragment(): F =
        supportFragmentManager.fragments.filterIsInstance<F>().first()

    fun showTab(tab: Tab) {
        on { it.findViewById<ViewPager>(R.id.pager).currentItem = tab.ordinal }
        // The lists refresh on a timer while their tab is shown.
        idle(millis = REFRESH_MILLIS)
    }

    private fun MainActivity.list(tab: Tab): ListView =
        when (tab) {
            Tab.FOLDERS -> fragment<FolderListFragment>()
            Tab.DEVICES -> fragment<DeviceListFragment>()
            Tab.STATUS -> fragment<StatusFragment>()
        }.listView

    private fun MainActivity.row(tab: Tab, position: Int): View {
        val list = list(tab)
        return list.adapter.getView(position, null, list)
    }

    private fun MainActivity.position(tab: Tab, title: String): Int {
        val adapter = list(tab).adapter
        return (0 until adapter.count).single {
            when (val item = adapter.getItem(it)) {
                is Folder -> item.toString() == title
                is Device -> item.displayName == title
                else -> item.toString() == title
            }
        }
    }

    /** The folders listed, by label. */
    fun folders(): List<String> = on { a ->
        val adapter = a.list(Tab.FOLDERS).adapter
        (0 until adapter.count).map { (adapter.getItem(it) as Folder).toString() }
    }

    /** The state a folder's row shows, or null without one. */
    fun folderState(label: String): String? = on { a ->
        val state =
            a.row(Tab.FOLDERS, a.position(Tab.FOLDERS, label)).findViewById<TextView>(R.id.state)
        state.text.toString().takeIf { state.visibility == View.VISIBLE }
    }

    fun devices(): List<String> = on { a ->
        val adapter = a.list(Tab.DEVICES).adapter
        (0 until adapter.count).map { (adapter.getItem(it) as Device).displayName }
    }

    fun deviceStatus(name: String): String = on { a ->
        a.row(Tab.DEVICES, a.position(Tab.DEVICES, name))
            .findViewById<TextView>(R.id.status)
            .text
            .toString()
    }

    private fun nextStarted(): Intent? =
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity

    /** Taps the row with [title] on [tab] and returns the screen it started. */
    fun open(tab: Tab, title: String): Intent? {
        on { a ->
            val list = a.list(tab)
            val position = a.position(tab, title)
            list.performItemClick(a.row(tab, position), position, 0)
        }
        return nextStarted()
    }

    /** The actions in the top bar of each tab. */
    enum class Action(val tab: Tab, val id: Int) {
        ADD_FOLDER(Tab.FOLDERS, R.id.add_folder),
        RESCAN_ALL(Tab.FOLDERS, R.id.rescan_all),
        ADD_DEVICE(Tab.DEVICES, R.id.add_device),
        SETTINGS(Tab.STATUS, R.id.open_preferences),
    }

    /** Runs [action] and returns the screen it started, if any. */
    fun run(action: Action): Intent? {
        on { a ->
            when (action.tab) {
                Tab.FOLDERS -> a.fragment<FolderListFragment>()
                Tab.DEVICES -> a.fragment<DeviceListFragment>()
                Tab.STATUS -> a.fragment<StatusFragment>()
            }.onOptionsItemSelected(RoboMenuItem(action.id))
        }
        return nextStarted()
    }

    fun statusLines(): List<String> = on { a ->
        val adapter = a.list(Tab.STATUS).adapter
        (0 until adapter.count).map { adapter.getItem(it).toString() }
    }

    /** Which of "follow run conditions", "force start", "force stop" is chosen. */
    fun forceStartStop(): Int = on {
        it.findViewById<SegmentedButton>(R.id.forceStartStop).selectedButtonIndex
    }

    fun chooseForceStartStop(index: Int) = on {
        it.findViewById<SegmentedButton>(R.id.forceStartStop).getChildAt(index).performClick()
    }

    fun light(): Light = on { a ->
        Light.entries.single {
            val id =
                when (it) {
                    Light.ACTIVE -> R.id.btnActive
                    Light.STARTING -> R.id.btnStarting
                    Light.DISABLED -> R.id.btnDisabled
                }
            a.findViewById<View>(id).visibility == View.VISIBLE
        }
    }

    /** The overall sync progress shown in the top bar, or null when hidden. */
    fun totalProgress(): String? = on { a ->
        a.findViewById<TextView>(R.id.tvTotalSyncComplete).text.toString().takeIf {
            a.findViewById<View>(R.id.topRelTotalSyncProgress).visibility == View.VISIBLE
        }
    }

    fun exitShown(): Boolean = on { it.findViewById<View>(R.id.fabExit).visibility == View.VISIBLE }

    fun exit() = on { it.findViewById<View>(R.id.fabExit).performClick() }

    fun usageReportingAsked(): Boolean =
        (ShadowDialog.getLatestDialog() as? AlertDialog)?.isShowing == true

    /** Answers the usage reporting question: yes, no, or open the website. */
    fun answerUsageReporting(answer: Int) {
        val button =
            when (answer) {
                YES -> DialogInterface.BUTTON_POSITIVE
                NO -> DialogInterface.BUTTON_NEGATIVE
                else -> DialogInterface.BUTTON_NEUTRAL
            }
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(button).performClick()
        idle()
    }

    fun openDrawer() = on {
        it.findViewById<DrawerLayout>(R.id.drawer_layout).openDrawer(GravityCompat.START, false)
    }

    fun drawerOpen(): Boolean = on {
        it.findViewById<DrawerLayout>(R.id.drawer_layout).isDrawerOpen(GravityCompat.START)
    }

    fun back() {
        on { it.onBackPressedDispatcher.onBackPressed() }
        // The drawer closes with an animation, which runs as the drawer is drawn: Robolectric
        // does not draw, so step it here.
        repeat(ANIMATION_STEPS) {
            idle(millis = ANIMATION_STEP_MILLIS)
            scenario.onActivity { activity ->
                activity.findViewById<DrawerLayout>(R.id.drawer_layout).computeScroll()
            }
        }
        idle()
    }

    fun finishing(): Boolean = on { it.isFinishing }

    override fun close() = scenario.close()

    companion object {
        const val YES = 0
        const val NO = 1
        const val WEBSITE = 2

        /** Long enough for the lists' timer to refresh them once. */
        private const val REFRESH_MILLIS = 1_100L

        private const val ANIMATION_STEPS = 30
        private const val ANIMATION_STEP_MILLIS = 20L
    }
}
