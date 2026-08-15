package com.intempt.core.types

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No printing path may emit the ingestion key.
 *
 * Three of the five Intempt SDKs shipped this bug independently — PHP through a `public readonly`
 * property that `print_r` walks, Python through `repr` on its resolved config, Android through the
 * `toString` Kotlin generates for a data class. That made it a contract rule rather than three bug
 * reports, and this is the Android half of enforcing it.
 *
 * The secret is planted rather than referenced: [SECRET] is a literal that appears nowhere else in
 * the SDK, so a test that passes because the redaction works and a test that passes because it
 * asserted the wrong string are distinguishable. Swift's equivalent only found its second leaking
 * path because someone checked the test could fail.
 */
class CredentialRedactionTest {
    private companion object {
        const val SECRET = "sk-live-3f9c2a-DO-NOT-PRINT"
        const val KEY = "keyid.$SECRET"
    }

    @Test
    fun `IntemptConfigs never prints the api key`() {
        val configs =
            IntemptConfigs(
                apiKey = KEY,
                sourceId = "src-1",
                organizationId = "org-1",
                projectId = "proj-1",
            )

        val printed = configs.toString()

        assertFalse("the secret reached toString: $printed", printed.contains(SECRET))
        assertFalse("the whole key reached toString: $printed", printed.contains(KEY))
        assertTrue("redaction marker missing: $printed", printed.contains("apiKey=***"))

        // The non-secret fields must survive. A toString that redacts by printing nothing is
        // useless for the debugging it exists for, and would pass the assertions above.
        assertTrue(printed.contains("src-1"))
        assertTrue(printed.contains("org-1"))
        assertTrue(printed.contains("proj-1"))
    }

    @Test
    fun `IntemptCredentials never prints the api key`() {
        val credentials =
            IntemptCredentials(
                apiKey = KEY,
                organizationId = "org-1",
                projectId = "proj-1",
                sourceId = "src-1",
            )

        val printed = credentials.toString()

        assertFalse("the secret reached toString: $printed", printed.contains(SECRET))
        assertTrue("redaction marker missing: $printed", printed.contains("apiKey=***"))
        assertTrue(printed.contains("org-1"))
    }

    /**
     * Interpolating the object is the exact line that would leak it, and it is the one a reviewer
     * waves through. Asserted separately from `toString()` because Kotlin's `"$obj"` and
     * `obj.toString()` are the same call today and there is nothing guaranteeing a future
     * `IntemptConfigs` keeps them that way.
     */
    @Test
    fun `string interpolation does not leak the key`() {
        val configs = IntemptConfigs(KEY, "src-1", "org-1", "proj-1")
        val credentials = IntemptCredentials(KEY, "org-1", "proj-1", "src-1")

        assertFalse("$configs".contains(SECRET))
        assertFalse("$credentials".contains(SECRET))
    }

    /**
     * No credential accessor may appear in the declared public API.
     *
     * `QueueConfig.getAuthorization()` was public and returned the base64 ingestion credential —
     * the same defect as `ConfigManagerService.token()`, which was public API returning the same
     * secret, and it was sitting in `app.api` where a reviewer would have to recognise the name to
     * catch it. Every caller was inside `com.intempt.core.queue`, so nothing needed it public.
     *
     * **This test alone does not catch a source-level regression, and it is worth knowing why.**
     * It reads the checked-in `app/api/app.api`, so making `getAuthorization` public again leaves
     * it green — verified, the falsification harness reports HOLLOW for exactly that mutation.
     * The protection is a two-step chain, and both steps were checked empirically:
     *
     * 1. `apiCheck` fails on the drift (verified: `+ public fun getAuthorization ()...`, exit 1),
     *    and it runs in CI. The only way past it is regenerating the dump.
     * 2. This test fails on the regenerated dump (verified: a planted accessor line turns it red).
     *
     * So a new credential accessor cannot reach `main` green, but it takes both. Deleting either
     * one silently reopens the hole — which is why this comment names them rather than leaving the
     * next reader to infer that a passing test here means more than it does.
     *
     * The allowance is `IntemptCredentials.getApiKey`, which returns what the caller passed in and
     * has no secret of the SDK's to leak.
     */
    @Test
    fun `the declared api surface exposes no credential accessor`() {
        val dump = java.io.File("api/app.api")

        // Asserted, not assumed. If the dump moves, this test must fail rather than pass by
        // finding nothing to object to — a guard that silently stops reading its input is worse
        // than no guard, because it still reports green.
        assertTrue("app.api not found at ${dump.absolutePath}; this guard reads nothing", dump.isFile)

        // Members in app.api are indented under their class, so the enclosing class has to be
        // tracked to scope the allowance to IntemptCredentials. Matching on the member line alone
        // would allow a `getApiKey` on any class at all, which is the hole this test exists to
        // close rather than reproduce.
        val allowed = "com/intempt/core/types/IntemptCredentials"
        var enclosing = ""
        val offenders = mutableListOf<String>()

        dump.readLines().forEach { line ->
            if (!line.startsWith("\t") && line.contains("class ")) {
                enclosing = line
                return@forEach
            }
            if (!line.contains("public")) return@forEach
            if (enclosing.contains(allowed)) return@forEach

            // Match the MEMBER NAME, not the whole line, and require it to hand back a String.
            //
            // The first version matched anywhere in the line, which made
            // `IntemptError.MalformedApiKey.copy(I)` an offender — it takes the key's *length* and
            // leaks nothing, and matched only because its own return type's class name contains
            // "ApiKey". A guard that cries wolf on a type it was written to bless is a guard
            // someone deletes. Narrowing to name-plus-return-type keeps
            // `getAuthorization()Ljava/lang/String;` caught, which is the one that was real.
            val member = line.trim().substringAfter("fun ", "").substringBefore(" ")
            val field = line.trim().substringAfter("field ", "").substringBefore(" ")
            val name = member.ifEmpty { field }
            if (name.isEmpty()) return@forEach
            if (listOf("ApiKey", "Token", "Authorization", "Secret", "Credential").none { name.contains(it) }) {
                return@forEach
            }
            if (!line.contains("Ljava/lang/String;")) return@forEach

            offenders += "${enclosing.substringAfter("class ").substringBefore(" ")} ${line.trim()}"
        }

        assertTrue(
            "credential accessors in the declared API:\n${offenders.joinToString("\n")}",
            offenders.isEmpty(),
        )
    }

    /**
     * `problems()` names what is wrong with a key without quoting it.
     *
     * The contract's third credential rule: an error never carries key material. An SDK that
     * refuses a malformed key by echoing it puts the key wherever the error goes — a crash
     * reporter, a support ticket, a screenshot.
     */
    @Test
    fun `a malformed key is reported without quoting it`() {
        val problems = IntemptCredentials("no-separator-$SECRET", "org-1", "proj-1", "src-1").problems()

        assertEquals(1, problems.size)
        assertFalse("the rejection message quoted the key: ${problems.first()}", problems.first().contains(SECRET))
        assertTrue(problems.first().contains("apiKey"))
    }
}
