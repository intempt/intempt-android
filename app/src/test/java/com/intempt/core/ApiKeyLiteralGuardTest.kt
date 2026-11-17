package com.intempt.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * INT-6972 / INT-6973: real API keys were committed in unit-test fixtures and flagged by Aikido.
 * Fails on any quoted "<id>.<secret>" literal with a 16+ character id and a 32+ character secret
 * in the module sources, docs or scripts; fixtures use [FAKE_API_KEY] instead.
 */
class ApiKeyLiteralGuardTest {
    private val keyLiteral = Regex("\"([A-Za-z0-9]{16,}\\.[A-Za-z0-9]{32,})\"")
    private val scanned = setOf("kt", "kts", "java", "json", "xml", "md", "sh", "py", "properties")

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return requireNotNull(dir) { "settings.gradle.kts not found above ${File("").absolutePath}" }
    }

    @Test
    fun `fake key has the shape the SDK accepts`() {
        val parts = FAKE_API_KEY.split(".")
        assertEquals(2, parts.size)
        assertEquals(32, parts[0].length)
        assertEquals(32, parts[1].length)
    }

    @Test
    fun `no real-shaped api key literal is committed`() {
        val root = repoRoot()
        val roots =
            listOf("app/src", "push/src", "sample/src", "baselineprofile/src", "mutation/src", "docs", "scripts")
                .map { File(root, it) } + root.listFiles { f -> f.isFile && f.extension == "md" }.orEmpty()
        val files = roots.flatMap { r -> r.walkTopDown().filter { it.isFile && it.extension in scanned }.toList() }
        assertTrue("scanned nothing under $root", files.any { it.name == "TestCredentials.kt" })

        // The match is reported by its first 4 characters only, so a failure does not re-leak the key.
        val hits =
            files.flatMap { f ->
                f.readLines().withIndex().flatMap { (i, line) ->
                    keyLiteral.findAll(line)
                        .map { it.groupValues[1] }
                        .filter { it != FAKE_API_KEY }
                        .map { "${f.relativeTo(root)}:${i + 1} ${it.take(4)}…" }
                        .toList()
                }
            }
        assertTrue("API-key-shaped literals found; use FAKE_API_KEY:\n" + hits.joinToString("\n"), hits.isEmpty())
    }
}
