package com.intempt.core.types

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runtime credentials: what counts as usable, and what the type is allowed to print.
 *
 * Split out of `CredentialRedactionTest` so it has no Android dependency and no filesystem read,
 * which lets the `:mutation` module run it. Mutation testing found this type at **zero coverage**
 * with 17 live mutants — every validation branch could have been inverted and nothing would have
 * failed. That is worse than it sounds for a type whose whole job is rejecting a bad key before it
 * reaches the auth path.
 */
class IntemptCredentialsTest {
    private companion object {
        const val SECRET = "sk-live-1a2b-DO-NOT-PRINT"
        const val KEY = "keyid.$SECRET"
    }

    private fun credentials(
        apiKey: String = KEY,
        organizationId: String = "org-1",
        projectId: String = "proj-1",
        sourceId: String = "src-1",
    ) = IntemptCredentials(apiKey, organizationId, projectId, sourceId)

    @Test
    fun `complete credentials are usable`() {
        val credentials = credentials()

        assertTrue(credentials.problems().isEmpty())
        assertTrue(credentials.isValid)
    }

    /**
     * Each blank field is reported by name, and independently.
     *
     * One `problems()` that returns a generic "invalid" would satisfy a test that only checks
     * `isValid`, and would tell an integrator nothing about which of the four to go and fix.
     */
    @Test
    fun `each blank identifier is named`() {
        assertTrue(credentials(organizationId = "").problems().any { it.contains("organizationId") })
        assertTrue(credentials(projectId = "").problems().any { it.contains("projectId") })
        assertTrue(credentials(sourceId = "").problems().any { it.contains("sourceId") })
        assertTrue(credentials(apiKey = "").problems().any { it.contains("apiKey") })

        listOf(
            credentials(organizationId = ""),
            credentials(projectId = ""),
            credentials(sourceId = ""),
            credentials(apiKey = ""),
        ).forEach { assertFalse(it.isValid) }
    }

    /** Whitespace is not a value. A key of spaces would otherwise pass a `isEmpty` check. */
    @Test
    fun `whitespace-only identifiers are blank`() {
        assertFalse(credentials(organizationId = "   ").isValid)
        assertFalse(credentials(sourceId = "\t").isValid)
    }

    @Test
    fun `every missing field is reported at once, not just the first`() {
        val problems = IntemptCredentials("", "", "", "").problems()

        assertTrue("expected all four to be named, got $problems", problems.size >= 4)
    }

    /**
     * The `<id>.<secret>` shape is checked here rather than discovered at first send.
     *
     * The old code destructured `apiKey.split(".")` straight into two variables, so a key without a
     * dot threw IndexOutOfBoundsException from inside the auth path — a typo taking down the host
     * app rather than being reported.
     */
    @Test
    fun `a key without the separator is rejected`() {
        assertFalse(credentials(apiKey = "no-separator").isValid)
        assertFalse("an empty id half is not a key", credentials(apiKey = ".secret").isValid)
        assertFalse("an empty secret half is not a key", credentials(apiKey = "keyid.").isValid)
        assertFalse("three parts is not the documented shape", credentials(apiKey = "a.b.c").isValid)
    }

    @Test
    fun `a blank key half is rejected as well as an empty one`() {
        assertFalse(credentials(apiKey = "   .secret").isValid)
        assertFalse(credentials(apiKey = "keyid.   ").isValid)
    }

    /** The rejection names the field but never quotes the value. */
    @Test
    fun `a malformed key is reported without quoting it`() {
        val problems = credentials(apiKey = "no-separator-$SECRET").problems()

        assertEquals(1, problems.size)
        assertFalse("the message quoted the key: ${problems.first()}", problems.first().contains(SECRET))
        assertTrue(problems.first().contains("apiKey"))
    }

    // ------------------------------------------------------------------- printing

    @Test
    fun `toString redacts the key and keeps the rest`() {
        val printed = credentials().toString()

        assertFalse("the secret reached toString: $printed", printed.contains(SECRET))
        assertFalse("the whole key reached toString: $printed", printed.contains(KEY))
        assertTrue("redaction marker missing: $printed", printed.contains("apiKey=***"))

        // Redacting by printing nothing would pass the assertions above and be useless for the
        // debugging this exists for.
        assertTrue(printed.contains("org-1"))
        assertTrue(printed.contains("proj-1"))
        assertTrue(printed.contains("src-1"))
    }

    @Test
    fun `string interpolation does not leak the key`() {
        assertFalse("${credentials()}".contains(SECRET))
    }

    /**
     * Equality still works on the whole value.
     *
     * Worth pinning because the obvious way to "fix" a leaking `toString` on a data class is to stop
     * it being a data class, which would silently take `equals` with it — and a bridge comparing the
     * credentials it just built against the ones it holds would start seeing them as different.
     */
    @Test
    fun `credentials compare by value`() {
        assertEquals(credentials(), credentials())
        assertEquals(credentials().hashCode(), credentials().hashCode())
        assertFalse(credentials() == credentials(projectId = "other"))
    }
}
