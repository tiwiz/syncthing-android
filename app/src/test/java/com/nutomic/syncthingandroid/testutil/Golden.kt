package com.nutomic.syncthingandroid.testutil

import java.io.File
import org.junit.Assert.assertEquals

/**
 * Golden-file assertions for characterization tests.
 *
 * The expected output lives in `src/test/resources/golden/<name>`. Run the tests with
 * `-Pgolden.record=true` (or the `GOLDEN_RECORD=true` environment variable) to write the current
 * output instead of comparing, then review the diff before committing.
 */
object Golden {

    private val goldenDir = File("src/test/resources/golden")

    private val record: Boolean
        get() =
            System.getProperty("golden.record").toBoolean() ||
                System.getenv("GOLDEN_RECORD").toBoolean()

    fun assertMatches(name: String, actual: String) {
        val file = File(goldenDir, name)
        val normalized = actual.replace("\r\n", "\n").trimEnd() + "\n"
        if (record) {
            file.parentFile?.mkdirs()
            file.writeText(normalized)
            return
        }
        check(file.exists()) {
            "Golden file ${file.path} is missing. Record it with GOLDEN_RECORD=true."
        }
        assertEquals("Golden mismatch for $name", file.readText(), normalized)
    }
}
