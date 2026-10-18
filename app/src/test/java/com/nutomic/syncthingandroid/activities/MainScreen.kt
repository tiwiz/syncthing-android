package com.nutomic.syncthingandroid.activities

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.KeyEvent
import androidx.activity.BackEventCompat
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import org.robolectric.Shadows.shadowOf

/**
 * Drives the main screen the way a user would, so the tests stay the same whatever the screen is
 * made of.
 */
internal class MainScreen(
    private val scenario: ActivityScenario<MainActivity>,
    private val compose: ComposeTestRule,
) : AutoCloseable {

    enum class Tab(val title: String) {
        FOLDERS("Folders"),
        DEVICES("Devices"),
        STATUS("Status"),
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
        compose.waitForIdle()
    }

    fun showTab(tab: Tab) {
        compose.onNode(hasText(tab.title) and hasClickAction()).performClick()
        // The screen refreshes on a timer.
        idle(millis = REFRESH_MILLIS)
    }

    private fun texts(tagPrefix: String): List<String> =
        compose.onAllNodes(hasTestTagStartingWith(tagPrefix)).fetchSemanticsNodes().map {
            it.config[SemanticsProperties.TestTag].removePrefix(tagPrefix)
        }

    private fun hasTestTagStartingWith(prefix: String) =
        SemanticsMatcher("TestTag starts with $prefix") {
            it.config.getOrElseNullable(SemanticsProperties.TestTag) { null }?.startsWith(prefix) ==
                true
        }

    /** The folders listed, by label. */
    fun folders(): List<String> = texts("folder:")

    /** The state a folder's row shows, or null without one. */
    fun folderState(label: String): String? =
        compose
            .onAllNodesWithTag("folderState:$label", useUnmergedTree = true)
            .fetchSemanticsNodes()
            .firstOrNull()
            ?.config
            ?.get(SemanticsProperties.Text)
            ?.joinToString()

    fun devices(): List<String> = texts("device:")

    /** Whether the row of folder [label] is marked as the one open in the detail pane. */
    fun folderSelected(label: String): Boolean = rowSelected("folder:$label")

    /** Whether the row of device [name] is marked as the one open in the detail pane. */
    fun deviceSelected(name: String): Boolean = rowSelected("device:$name")

    private fun rowSelected(tag: String): Boolean =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrElse(
            SemanticsProperties.Selected
        ) {
            false
        }

    fun deviceStatus(name: String): String =
        compose
            .onNodeWithTag("deviceStatus:$name", useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString()

    private fun nextStarted(): Intent? =
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity

    /** Taps the row with [title] on [tab] and returns the screen it started. */
    fun open(tab: Tab, title: String): Intent? {
        val tag = if (tab == Tab.FOLDERS) "folder:$title" else "device:$title"
        compose.onNodeWithTag(tag).performClick()
        idle()
        return nextStarted()
    }

    /** The actions in the top bar and the add button's menu. */
    enum class Action(val description: String, val inAddMenu: Boolean = false) {
        ADD_FOLDER("Add Folder", inAddMenu = true),
        RESCAN_ALL("Rescan All"),
        ADD_DEVICE("Add Device", inAddMenu = true),
        SETTINGS("Settings"),
    }

    /** Runs [action] and returns the screen it started, if any. */
    fun run(action: Action): Intent? {
        if (action.inAddMenu) {
            compose.onNodeWithContentDescription("Add").performClick()
            idle()
            compose.onNode(hasText(action.description) and hasClickAction()).performClick()
        } else {
            compose.onNodeWithContentDescription(action.description).performClick()
        }
        idle()
        return nextStarted()
    }

    private fun tagged(tag: String): List<SemanticsNode> =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()

    private fun SemanticsNode.texts(): List<String> =
        config.getOrElseNullable(SemanticsProperties.Text) { null }?.map { it.text }
            ?: children.flatMap { it.texts() }

    /**
     * The status tab as lines of text: the headline, "Reason:" with one "- " line per reason, then
     * "label: value" per figure.
     */
    fun statusLines(): List<String> {
        val reasons = tagged("statusReason").map { it.texts().joinToString() }
        return tagged("statusHeadline").map { it.texts().joinToString() } +
            (if (reasons.isEmpty()) emptyList()
            else listOf("Reason:\n- " + reasons.joinToString("\n- "))) +
            tagged("statusFigure").map { it.texts().joinToString(": ") }
    }

    /** Which of "follow run conditions", "force start", "force stop" is chosen. */
    fun forceStartStop(): Int =
        (0..2).single {
            compose
                .onNodeWithTag("force:$it")
                .fetchSemanticsNode()
                .config[SemanticsProperties.Selected]
        }

    fun chooseForceStartStop(index: Int) {
        compose.onNodeWithTag("force:$index").performClick()
        idle()
    }

    fun light(): Light =
        Light.entries.single {
            compose.onAllNodesWithTag("light:${it.name}").fetchSemanticsNodes().isNotEmpty()
        }

    /** The overall sync progress shown in the top bar, or null when hidden. */
    fun totalProgress(): String? =
        compose
            .onAllNodesWithTag("totalProgress", useUnmergedTree = true)
            .fetchSemanticsNodes()
            .firstOrNull()
            ?.config
            ?.get(SemanticsProperties.Text)
            ?.joinToString()
            ?.removeSuffix("%")

    /** Answers yes when exit asks for confirmation. */
    fun confirmExit() {
        compose.onNode(hasText("Yes") and hasClickAction()).performClick()
        idle()
    }

    fun usageReportingAsked(): Boolean =
        compose
            .onAllNodes(hasText("Allow Anonymous Usage Reporting?"))
            .fetchSemanticsNodes()
            .isNotEmpty()

    /** Answers the usage reporting question: yes, no, or open the website. */
    fun answerUsageReporting(answer: Int) {
        val text =
            when (answer) {
                YES -> "Yes"
                NO -> "No"
                else -> "Open Website"
            }
        compose.onNode(hasText(text) and hasClickAction()).performClick()
        idle()
    }

    /** The entries of the top bar's overflow menu, by label. */
    enum class MenuItem(val label: String) {
        DEVICE_ID("Show device ID"),
        RECENT_CHANGES("Recent changes"),
        WEB_GUI("Web GUI"),
        BACKUP("Import and Export"),
        RESTART("Restart"),
        EXIT("Exit"),
    }

    fun openMenu() {
        compose.onNodeWithContentDescription("More options").performClick()
        idle()
    }

    fun menuOpen(): Boolean =
        compose.onAllNodesWithTag("mainMenu").fetchSemanticsNodes().isNotEmpty()

    private fun menuNode(item: MenuItem) =
        compose.onNode(
            hasText(item.label) and hasClickAction() and hasAnyAncestor(hasTestTag("mainMenu"))
        )

    fun menuItemEnabled(item: MenuItem): Boolean =
        SemanticsProperties.Disabled !in menuNode(item).fetchSemanticsNode().config

    /** Chooses [item] in the open menu and returns the screen it started, if any. */
    fun choose(item: MenuItem): Intent? {
        menuNode(item).performClick()
        idle()
        return nextStarted()
    }

    /** The hardware menu key, as on a TV remote. */
    fun pressMenuKey() {
        scenario.onActivity {
            it.onKeyDown(
                KeyEvent.KEYCODE_MENU,
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU),
            )
        }
        idle()
    }

    /** The title of the screen in the detail pane, or null without one. */
    fun detailTitle(): String? =
        compose
            .onAllNodes(
                hasTestTag("editTitle") and hasAnyAncestor(hasTestTag("detailPane")),
                useUnmergedTree = true,
            )
            .fetchSemanticsNodes()
            .firstOrNull()
            ?.config
            ?.get(SemanticsProperties.Text)
            ?.joinToString()

    /** Types [label] into the folder screen in the detail pane. */
    fun setDetailFolderLabel(label: String) {
        compose.onNodeWithTag("label").performScrollTo().performTextReplacement(label)
        idle()
    }

    /** The label in the folder screen in the detail pane. */
    fun detailFolderLabel(): String =
        compose
            .onNodeWithTag("label")
            .performScrollTo()
            .fetchSemanticsNode()
            .config[SemanticsProperties.EditableText]
            .text

    /** Where the destination [tab] is drawn, in pixels. */
    fun tabBounds(tab: Tab): Rect =
        compose.onNode(hasText(tab.title) and hasClickAction()).fetchSemanticsNode().boundsInRoot

    /** The size of the whole screen, in pixels. */
    fun rootSize(): IntSize = compose.onRoot().fetchSemanticsNode().size

    /** How tall the title above the list of the current tab is, in pixels. */
    fun listTitleHeight(): Float =
        compose.onNodeWithTag("listTitle").fetchSemanticsNode().boundsInRoot.height

    /** Whether any field on the screen has the input focus, which brings up the keyboard. */
    fun anyFieldFocused(): Boolean =
        compose
            .onAllNodes(
                SemanticsMatcher.expectValue(SemanticsProperties.Focused, true) and
                    SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)
            )
            .fetchSemanticsNodes()
            .isNotEmpty()

    fun discardAsked(): Boolean =
        compose.onAllNodes(hasText("Discard your changes?")).fetchSemanticsNodes().isNotEmpty()

    fun confirmDiscard() {
        compose.onNode((hasText("OK") or hasText("Yes")) and hasClickAction()).performClick()
        idle()
    }

    fun back() {
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        idle()
    }

    /** Starts a back gesture from the left edge and holds it at [progress] (0 to 1). */
    fun startBackGesture(progress: Float) {
        scenario.onActivity {
            it.onBackPressedDispatcher.dispatchOnBackStarted(backEvent(0f))
            it.onBackPressedDispatcher.dispatchOnBackProgressed(backEvent(progress))
        }
        idle()
    }

    /** Lets go of the back gesture without going back. */
    fun cancelBackGesture() {
        scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackCancelled() }
        idle()
    }

    private fun backEvent(progress: Float) =
        BackEventCompat(
            touchX = 0f,
            touchY = 0f,
            progress = progress,
            swipeEdge = BackEventCompat.EDGE_LEFT,
        )

    /** How wide the detail pane is drawn, in pixels; null without one. */
    fun detailPaneWidth(): Float? =
        compose
            .onAllNodesWithTag("detailPane")
            .fetchSemanticsNodes()
            .firstOrNull()
            ?.boundsInRoot
            ?.width

    fun finishing(): Boolean {
        var finishing = false
        scenario.onActivity { finishing = it.isFinishing }
        return finishing
    }

    /** Recreates the screen, as a rotation or a fold does. */
    fun recreate() {
        scenario.recreate()
        idle()
    }

    override fun close() = scenario.close()

    companion object {
        const val YES = 0
        const val NO = 1
        const val WEBSITE = 2

        /** Long enough for the screen's timer to refresh it once. */
        private const val REFRESH_MILLIS = 1_100L
    }
}
