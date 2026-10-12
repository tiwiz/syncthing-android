package com.nutomic.syncthingandroid.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutomic.syncthingandroid.testutil.Golden
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileUtilsTest {

    @Test
    fun `cut trailing slash`() {
        assertEquals("/storage/emulated/0", FileUtils.cutTrailingSlash("/storage/emulated/0/"))
        assertEquals("/storage/emulated/0", FileUtils.cutTrailingSlash("/storage/emulated/0"))
        assertEquals("", FileUtils.cutTrailingSlash("/"))
        assertEquals("a/", FileUtils.cutTrailingSlash("a//"))
    }

    @Test
    fun `syncthing home is a folder in external storage`() {
        assertTrue(FileUtils.getSyncthingTildeAbsolutePath().endsWith("/syncthing"))
    }

    @Test
    fun `mime types by extension`() {
        val extensions =
            listOf(
                "jpg",
                "JPG",
                "jpeg",
                "png",
                "mp4",
                "mkv",
                "pdf",
                "txt",
                "md",
                "apk",
                "zip",
                "7z",
                "",
                "unknownext",
            )
        Golden.assertMatches(
            "util/mime-types.txt",
            extensions.joinToString("\n") {
                "[$it] -> ${FileUtils.getMimeTypeFromFileExtension(it)}"
            },
        )
    }

    @Test
    fun `delete directory recursively`() {
        val root = Files.createTempDirectory("fileutils").toFile()
        File(root, "a/b").mkdirs()
        File(root, "a/b/file.txt").writeText("x")
        File(root, "top.txt").writeText("y")

        assertTrue(FileUtils.deleteDirectoryRecursively(root))
        assertFalse(root.exists())
        assertFalse(FileUtils.deleteDirectoryRecursively(root))
        assertFalse(FileUtils.deleteDirectoryRecursively(null))
    }
}
