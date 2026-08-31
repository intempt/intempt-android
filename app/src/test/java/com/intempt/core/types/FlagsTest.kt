package com.intempt.core.types

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The flag surface's pure decisions.
 *
 * These live here rather than beside the Android classes they were extracted from because
 * `:mutation` compiles this file: `Flags.kt` has no Android dependency, so PIT can reach it, and
 * every function below is the null/branch shape mutation testing exists for. Before this file the
 * mutation gate's `targetClasses` allowlist matched **none** of the six classes the flag change
 * touched, so its 85/85 was measured entirely over code the change did not go near.
 *
 * Each assertion below is written to fail if the line it covers is inverted or deleted — see
 * `docs/TESTING.md`, "a test that has never failed has never been tested".
 */
class FlagsTest {
    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    // ---- buildChooseBody ------------------------------------------------------------------

    @Test
    fun `body carries sourceId, device and the named keys`() {
        val body = buildChooseBody("src-1", "prof-1", null, listOf("a", "b"))

        assertEquals("mobile", body["device"])
        assertEquals(listOf("a", "b"), body["names"])

        @Suppress("UNCHECKED_CAST")
        val id = body["identification"] as Map<String, Any>
        assertEquals("src-1", id["sourceId"])
        assertEquals("prof-1", id["profileId"])
    }

    /**
     * The distinction the whole method turns on: a null `names` asks for EVERY key, an empty list
     * asks for none. Sending `[]` where null was meant returns nothing and looks like "no flags".
     */
    @Test
    fun `null names omits the key entirely rather than sending an empty list`() {
        val all = buildChooseBody("src-1", "prof-1", null, null)
        assertTrue("names must be absent, not empty", !all.containsKey("names"))

        val none = buildChooseBody("src-1", "prof-1", null, emptyList())
        assertEquals(emptyList<String>(), none["names"])
    }

    @Test
    fun `blank and null identifiers are omitted, not sent as empty strings`() {
        @Suppress("UNCHECKED_CAST")
        val id = buildChooseBody("src-1", "   ", "", null)["identification"] as Map<String, Any>

        assertEquals(setOf("sourceId"), id.keys)
    }

    /**
     * `userId` is sent when supplied. Asserted rather than assumed because of what it does: the
     * serving side's `Identification.anonymousId()` PREFERS `userId` over `sourceId_profileId`, so
     * this key changes the identifier assignment is derived on. See the warning on [FlagContext].
     */
    @Test
    fun `userId is sent when supplied, and it changes the derived identity`() {
        @Suppress("UNCHECKED_CAST")
        val id = buildChooseBody("src-1", "prof-1", "user-9", null)["identification"] as Map<String, Any>

        assertEquals("user-9", id["userId"])
        assertEquals("prof-1", id["profileId"])
    }

    // ---- unwrapFlagValue ------------------------------------------------------------------

    @Test
    fun `types are preserved and never coerced`() {
        assertEquals("on", unwrapFlagValue(obj("""{"v":"on"}""")["v"]!!))
        assertEquals(true, unwrapFlagValue(obj("""{"v":true}""")["v"]!!))
        assertEquals(3L, unwrapFlagValue(obj("""{"v":3}""")["v"]!!))
        assertEquals(1.5, unwrapFlagValue(obj("""{"v":1.5}""")["v"]!!))
    }

    /**
     * The string `"true"` must stay a String. If it decoded as a Boolean, `boolVariation` would
     * return `true` for a misconfigured flag instead of falling back to the caller's default —
     * a wrong answer that is indistinguishable from a correct one.
     */
    @Test
    fun `a quoted boolean stays a string`() {
        val v = unwrapFlagValue(obj("""{"v":"true"}""")["v"]!!)
        assertTrue("expected String, got ${v?.javaClass}", v is String)
        assertEquals("true", v)
    }

    /** JSON null is a value, not an absence. It is what makes the L3 distinction in `variation` real. */
    @Test
    fun `json null unwraps to null`() {
        assertNull(unwrapFlagValue(obj("""{"v":null}""")["v"]!!))
    }

    @Test
    fun `an object or array is handed back whole`() {
        val v = unwrapFlagValue(obj("""{"v":{"nested":1}}""")["v"]!!)
        assertTrue(v is JsonObject)
    }

    // ---- flagNameOf / selectChoice --------------------------------------------------------

    @Test
    fun `the choice matching the key is selected`() {
        val choices = listOf(obj("""{"name":"a","body":1}"""), obj("""{"name":"b","body":2}"""))
        assertEquals("b", flagNameOf(selectChoice(choices, "b")!!))
        assertNull(selectChoice(choices, "c"))
    }

    /**
     * A malformed `name` must not throw. The previous implementation read `jsonPrimitive`, which
     * throws on a non-primitive, so a response shaped `{"name":{}}` propagated an exception out of
     * a public `variation()` call — the one thing `docs/CONVENTIONS.md` says a service problem
     * must never do.
     */
    @Test
    fun `a non-string name yields null instead of throwing`() {
        assertNull(flagNameOf(obj("""{"name":{},"body":1}""")))
        assertNull(flagNameOf(obj("""{"name":7,"body":1}""")))
        assertNull(flagNameOf(obj("""{"body":1}""")))
        assertNull(selectChoice(listOf(obj("""{"name":{},"body":1}""")), "a"))
    }

    // ---- FlagContext / FlagDetail ----------------------------------------------------------

    /**
     * The two shapes' accessors.
     *
     * Asserted here because `:mutation` compiles this file and nothing else it compiles reads them:
     * PIT reported `FlagContext.getUserId`, `FlagContext.getProfileId` and `FlagDetail.getValue` as
     * NO_COVERAGE, holding Flags.kt at 33/36. That was a hole in the gate rather than dead code —
     * `IntemptInstance` does read `FlagDetail.value` — but a getter outside the gate is a getter
     * nothing would notice returning "" or null.
     */
    @Test
    fun `the context and detail shapes return what they were given`() {
        val ctx = FlagContext(userId = "user-9", profileId = "prof-1")
        assertEquals("user-9", ctx.userId)
        assertEquals("prof-1", ctx.profileId)

        val defaults = FlagContext()
        assertNull(defaults.userId)
        assertNull(defaults.profileId)

        val detail = FlagDetail("on", FlagReason.TARGETED)
        assertEquals("on", detail.value)
        assertEquals(FlagReason.TARGETED, detail.reason)
    }

    // ---- FlagReason -----------------------------------------------------------------------

    /**
     * Pinned, not because it is reachable — it is not, see the note on [FlagReason] — but so that
     * the decoding is already under the gate on the day `EXP-SERVE-001` starts sending a reason.
     */
    @Test
    fun `every wire token decodes to its own reason`() {
        assertEquals(FlagReason.TARGETED, FlagReason.fromWire("targeted"))
        assertEquals(FlagReason.HOLDOUT, FlagReason.fromWire("holdout"))
        assertEquals(FlagReason.NOT_TARGETED, FlagReason.fromWire("not_targeted"))
        assertEquals(FlagReason.OFF, FlagReason.fromWire("off"))
    }

    @Test
    fun `an unknown or absent reason falls back to OFF rather than throwing`() {
        assertEquals(FlagReason.OFF, FlagReason.fromWire(null))
        assertEquals(FlagReason.OFF, FlagReason.fromWire("held_back"))
        assertEquals(FlagReason.OFF, FlagReason.fromWire("TARGETED"))
    }

    /** Today's real responses carry no `reason` key at all, so this is the only live path. */
    @Test
    fun `a real response with no reason key reads OFF`() {
        assertEquals(FlagReason.OFF, flagReasonOf(obj("""{"name":"a","body":1}""")))
        assertEquals(FlagReason.OFF, flagReasonOf(obj("""{"name":"a","reason":{}}""")))
        assertEquals(FlagReason.TARGETED, flagReasonOf(obj("""{"name":"a","reason":"targeted"}""")))
    }
}
