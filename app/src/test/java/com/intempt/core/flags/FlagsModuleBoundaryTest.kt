package com.intempt.core.flags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F33: the flag module imports only the core, never tracking. Android ships one AAR, so this is the
 * stand-in for Node's "build the flags entry with every tracking module deleted": no file in
 * `com.intempt.core.flags` may import a tracking package.
 */
class FlagsModuleBoundaryTest {
    private val tracking =
        listOf(
            "com.intempt.core.services.eventPool",
            "com.intempt.core.customCapture",
            "com.intempt.core.autocapture",
            "com.intempt.core.queue",
            "com.intempt.core.eventModels",
            "com.intempt.core.services.IntemptEventManagerService",
        )

    private fun moduleDir(): File =
        listOf("src/main", "app/src/main", "../app/src/main")
            .map { File(it, "java/com/intempt/core/flags") }
            .first { it.isDirectory }

    @Test
    fun `no file in the flags package imports a tracking package`() {
        val files = moduleDir().listFiles { f -> f.extension == "kt" }!!.toList()
        assertTrue("the scan must read files, not pass on an empty directory", files.size >= 4)
        val offending =
            files.flatMap { f ->
                f.readLines()
                    .filter { line -> line.startsWith("import ") && tracking.any { line.contains(it) } }
                    .map { "${f.name}: $it" }
            }
        assertEquals(emptyList<String>(), offending)
    }
}
