package com.nutomic.syncthingandroid.activities

import android.app.Activity
import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.testutil.ShadowEnvironmentWithAllFilesAccess
import com.nutomic.syncthingandroid.testutil.StoppedSyncthingService
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * [FolderPickerActivity] limited to a temporary root folder, so the listing does not depend on the
 * machine: listing, entering and leaving folders, creating one and selecting the result.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-rUS", shadows = [ShadowEnvironmentWithAllFilesAccess::class])
class FolderPickerActivityTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var root: File

    @Before
    fun setUp() {
        // The picker binds to the service, like every SyncthingActivity.
        StoppedSyncthingService.install()
        root =
            File(context.cacheDir, "picker-root").apply {
                deleteRecursively()
                File(this, "Music/Albums").mkdirs()
                File(this, "documents").mkdirs()
                File(this, "notes.txt").writeText("x")
                File(this, "A.txt").writeText("x")
            }
    }

    private fun launch(initial: String? = null) =
        ActivityScenario.launchActivityForResult<FolderPickerActivity>(
            FolderPickerActivity.createIntent(context, initial, root.path)
        )

    /** The names listed, in order; empty when the folder is. */
    private fun listed(): List<String> {
        val lists = compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes()
        if (lists.isEmpty()) return emptyList()
        return compose.onNode(hasScrollAction()).onChildren().fetchSemanticsNodes().map {
            it.config[SemanticsProperties.Text].joinToString()
        }
    }

    private fun currentPath(): String =
        compose
            .onNode(hasText("Current Path: ", substring = true))
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString()

    private fun open(name: String) {
        compose.onNodeWithText(name).performClick()
    }

    @Test
    fun `a single root is opened directly, folders first, names ignoring case`() {
        launch().use { scenario ->
            assertEquals(listOf("documents", "Music", "A.txt", "notes.txt"), listed())
            assertTrue(currentPath(), currentPath().endsWith(root.absolutePath))
        }
    }

    @Test
    fun `clicking a folder enters it, files do nothing`() {
        launch().use { scenario ->
            open("notes.txt")
            assertEquals(listOf("documents", "Music", "A.txt", "notes.txt"), listed())

            open("Music")
            assertEquals(listOf("Albums"), listed())
        }
    }

    @Test
    fun `going up stops at the root`() {
        launch(initial = File(root, "Music/Albums").path).use { scenario ->
            compose.onNodeWithContentDescription("Go up").performClick()
            assertEquals(listOf("Albums"), listed())
            compose.onNodeWithContentDescription("Go up").performClick()
            assertEquals(listOf("documents", "Music", "A.txt", "notes.txt"), listed())
            compose.onNodeWithContentDescription("Go up").performClick()
            assertEquals(listOf("documents", "Music", "A.txt", "notes.txt"), listed())

            scenario.onActivity {
                // A single root has no list of roots above it.
                assertEquals(false, it.canGoUpToSubDir())
                assertEquals(false, it.canGoUpToRootDir())
            }
        }
    }

    @Test
    fun `the folder shown survives a recreation`() {
        launch().use { scenario ->
            open("Music")

            scenario.recreate()

            assertEquals(listOf("Albums"), listed())
        }
    }

    @Test
    fun `select returns the folder shown`() {
        launch(initial = File(root, "Music").path).use { scenario ->
            compose.onNodeWithContentDescription("Select Folder").performClick()

            assertEquals(Activity.RESULT_OK, scenario.result.resultCode)
            // formatPath: a URI path, so a folder ends with a slash.
            assertEquals(
                File(root, "Music").absolutePath + "/",
                scenario.result.resultData.getStringExtra(
                    FolderPickerActivity.EXTRA_RESULT_DIRECTORY
                ),
            )
        }
    }

    @Test
    fun `a new folder is created and entered`() {
        launch().use { scenario ->
            compose.onNodeWithContentDescription("Create new Folder").performClick()
            compose.onNode(hasSetTextAction()).performTextInput("Photos")
            compose.onNodeWithText("OK").performClick()

            assertTrue(File(root, "Photos").isDirectory)
            assertTrue(currentPath(), currentPath().endsWith("Photos"))
            assertEquals(emptyList<String>(), listed())
            compose.onNodeWithText("Directory is Empty").assertExists()
        }
    }

    @Test
    fun `back cancels`() {
        launch().use { scenario ->
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

            assertEquals(Activity.RESULT_CANCELED, scenario.result.resultCode)
        }
    }
}
