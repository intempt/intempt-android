package com.intempt.core.types

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val body = buildChooseBody("src-1", "prof-1", null, listOf("a", "b"), "sess-1")

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
        val all = buildChooseBody("src-1", "prof-1", null, null, "sess-1")
        assertTrue("names must be absent, not empty", !all.containsKey("names"))

        val none = buildChooseBody("src-1", "prof-1", null, emptyList(), "sess-1")
        assertEquals(emptyList<String>(), none["names"])
    }

    @Test
    fun `blank and null identifiers are omitted, not sent as empty strings`() {
        @Suppress("UNCHECKED_CAST")
        val id = buildChooseBody("src-1", "   ", "", null, "sess-1")["identification"] as Map<String, Any>

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
        val id = buildChooseBody("src-1", "prof-1", "user-9", null, "sess-1")["identification"] as Map<String, Any>

        assertEquals("user-9", id["userId"])
        assertEquals("prof-1", id["profileId"])
    }

    /**
     * 4.1.0 (brain A1 (c)-ii). `audience-service`'s `ChooserHelper.allowOncePerSession` keys on this
     * field; without it a "Once per session" experience is served once EVER per profile. It is a
     * TOP-LEVEL key, a sibling of `identification`, matching `ExperienceApiChooseRequest.sessionId`
     * and what Swift sends — asserted on the key's position, not only its presence.
     */
    @Test
    fun `sessionId is sent top-level with the SDK's session value`() {
        val body = buildChooseBody("src-1", "prof-1", null, null, "sess-42")

        assertEquals("sess-42", body["sessionId"])

        @Suppress("UNCHECKED_CAST")
        val id = body["identification"] as Map<String, Any>
        assertTrue("sessionId belongs beside identification, not inside it", !id.containsKey("sessionId"))
    }

    /**
     * `StorageManagerService.getSessionId()` returns `""` before the first session starts. An empty
     * string on the wire would be STORED as the once-per-session key, so it is omitted instead.
     */
    @Test
    fun `blank or null sessionId is omitted rather than sent`() {
        assertTrue(!buildChooseBody("src-1", "prof-1", null, null, null).containsKey("sessionId"))
        assertTrue(!buildChooseBody("src-1", "prof-1", null, null, "  ").containsKey("sessionId"))
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

    /** Every reason audience-service `FlagValue.reason` names decodes to its own value (F6). */
    @Test
    fun `every wire token decodes to its own reason`() {
        assertEquals(FlagReason.TARGETED, FlagReason.fromWire("targeted"))
        assertEquals(FlagReason.HOLDOUT, FlagReason.fromWire("holdout"))
        assertEquals(FlagReason.NOT_TARGETED, FlagReason.fromWire("not_targeted"))
        assertEquals(FlagReason.NOT_IN_ROLLOUT, FlagReason.fromWire("not_in_rollout"))
        assertEquals(FlagReason.OFF, FlagReason.fromWire("off"))
        assertEquals(FlagReason.KILLED, FlagReason.fromWire("killed"))
        assertEquals(FlagReason.NOT_CLIENT_SIDE, FlagReason.fromWire("not_client_side"))
    }

    @Test
    fun `an unknown or absent reason falls back to OFF rather than throwing`() {
        assertEquals(FlagReason.OFF, FlagReason.fromWire(null))
        assertEquals(FlagReason.OFF, FlagReason.fromWire("held_back"))
        assertEquals(FlagReason.OFF, FlagReason.fromWire("TARGETED"))
    }

    // ---- flag errors (INT-7454, CONTRACT-R2 §S3) --------------------------------------------

    @Test
    fun `statuses classify as node and php do`() {
        assertEquals(FlagErrorKind.AUTH, flagErrorKindOf(401))
        assertEquals(FlagErrorKind.AUTH, flagErrorKindOf(403))
        assertEquals(FlagErrorKind.TIMEOUT, flagErrorKindOf(408))
        assertEquals(FlagErrorKind.SERVER, flagErrorKindOf(429))
        assertEquals(FlagErrorKind.SERVER, flagErrorKindOf(500))
        assertEquals(FlagErrorKind.SERVER, flagErrorKindOf(503))
        assertEquals(FlagErrorKind.BAD_REQUEST, flagErrorKindOf(499))
        assertEquals(FlagErrorKind.BAD_REQUEST, flagErrorKindOf(400))
        assertEquals(FlagErrorKind.BAD_REQUEST, flagErrorKindOf(404))
        assertEquals(FlagErrorKind.BAD_REQUEST, flagErrorKindOf(402))
    }

    @Test
    fun `every kind carries the contract's wire name`() {
        assertEquals(
            listOf("auth", "bad_request", "server", "timeout", "network", "parse"),
            FlagErrorKind.entries.map { it.wireName },
        )
    }

    @Test
    fun `a 2xx with a choices array is answered and keeps only object choices`() {
        val r = chooseResultOf("k", 200, """{"choices":[{"name":"k"},1]}""", timedOut = false)
        assertNull(r.error)
        assertEquals(listOf(obj("""{"name":"k"}""")), r.choices)
        assertNull(chooseResultOf("k", 299, """{"choices":[]}""", timedOut = false).error)
    }

    @Test
    fun `a 2xx whose body is not an object with a choices array is a parse error with its status`() {
        for (body in listOf(null, "", "not json", "[]", "{}", """{"choices":"x"}""", """{"choices":{}}""")) {
            val r = chooseResultOf("k", 200, body, timedOut = false)
            assertEquals(body, FlagErrorKind.PARSE, r.error?.kind)
            assertEquals(200, r.error?.status)
            assertTrue(r.choices.isEmpty())
        }
    }

    @Test
    fun `a non-2xx is classified with its status and the key, and its body is not read`() {
        val r = chooseResultOf("k", 300, """{"choices":[{"name":"k"}]}""", timedOut = false)
        assertEquals(FlagErrorKind.BAD_REQUEST, r.error?.kind)
        assertEquals(300, r.error?.status)
        assertEquals("k", r.error?.key)
        assertTrue(r.choices.isEmpty())
        assertEquals(FlagErrorKind.BAD_REQUEST, chooseResultOf("k", 199, "{}", timedOut = false).error?.kind)
    }

    @Test
    fun `no response is network, a timeout is timeout, both without a status`() {
        val net = chooseResultOf(null, 0, null, timedOut = false)
        assertEquals(FlagErrorKind.NETWORK, net.error?.kind)
        assertNull(net.error?.status)
        assertNull(net.error?.key)
        val slow = chooseResultOf("k", 0, null, timedOut = true)
        assertEquals(FlagErrorKind.TIMEOUT, slow.error?.kind)
        assertNull(slow.error?.status)
    }

    @Test
    fun `ERROR is never decoded from the wire`() {
        assertEquals("error", FlagReason.ERROR.wireValue)
        assertEquals(FlagReason.OFF, FlagReason.fromWire("error"))
        assertEquals("cached", FlagReason.CACHED.wireValue)
        assertEquals(FlagReason.OFF, FlagReason.fromWire("cached"))
    }

    @Test
    fun `the reporter logs the first auth failure at error naming the source, the rest at debug`() {
        val errors = mutableListOf<String>()
        val debug = mutableListOf<String>()
        val hooked = mutableListOf<FlagError>()
        val r = FlagErrorReporter({ "src-1" }, { errors += it }, { debug += it })
        val hook: (FlagError) -> Unit = { hooked += it }
        r.hook = hook
        assertTrue(r.hook === hook)

        r.report(FlagError("k", FlagErrorKind.AUTH, 401))
        r.report(FlagError("k", FlagErrorKind.AUTH, 403))
        r.report(FlagError(null, FlagErrorKind.SERVER, 500))

        assertEquals(1, errors.size)
        assertTrue(errors.single(), errors.single().contains("src-1"))
        assertTrue(errors.single(), errors.single().contains("401"))
        assertEquals(2, debug.size)
        assertTrue(errors.single(), errors.single().contains("for key k;"))
        assertTrue(debug[0], debug[0].contains("auth 403") && debug[0].contains("for key k;"))
        assertFalse(debug[1], debug[1].contains("key"))
        assertTrue(debug[1], debug[1].contains("server 500") && debug[1].contains("all flags"))
        assertEquals(listOf(401, 403, 500), hooked.map { it.status })
    }

    @Test
    fun `a throwing hook is logged and does not escape`() {
        val errors = mutableListOf<String>()
        val r = FlagErrorReporter({ "s" }, { errors += it }, { })
        r.hook = { throw IllegalStateException("boom") }

        r.report(FlagError("k", FlagErrorKind.NETWORK, null))

        assertEquals(1, errors.size)
        assertTrue(errors.single(), errors.single().contains("onFlagError") && errors.single().contains("boom"))
    }

    @Test
    fun `a flag error prints its key, wire kind and status`() {
        assertEquals(
            "FlagError(key=k, kind=timeout, status=408)",
            FlagError("k", FlagErrorKind.TIMEOUT, 408).toString(),
        )
        val d = FlagDetail("v", FlagReason.OFF)
        assertNull(d.error)
        assertNull(d.errorCode)
        assertNull(d.variationId)
        assertNull(d.valueType)
        assertNull(d.rule)
        assertNull(d.variant)
        assertEquals(
            "FlagDetail(value=v, reason=off, errorCode=null, variationId=null, valueType=null, rule=null, " +
                "variant=null, error=null)",
            d.toString(),
        )
        val full = FlagDetail(1, FlagReason.ERROR, null, FlagErrorCode.TYPE_MISMATCH, 9L, "number", 2, "b")
        assertEquals(FlagErrorCode.TYPE_MISMATCH, full.errorCode)
        assertEquals(9L, full.variationId)
        assertEquals("number", full.valueType)
        assertEquals(2, full.rule)
        assertEquals("b", full.variant)
        assertTrue(full.toString().contains("errorCode=TYPE_MISMATCH, variationId=9"))
    }

    // ---- exposure (INT-7456, CONTRACT-R2 §S4) -----------------------------------------------

    @Test
    fun `a recording read leaves the exposure field out, an evaluate-only read sends false`() {
        val body = mapOf<String, Any?>("device" to "mobile")
        assertEquals(body, withExposure(body, recordExposure = true))
        assertFalse(withExposure(body, recordExposure = true).containsKey("exposure"))
        assertEquals(mapOf("device" to "mobile", "exposure" to false), withExposure(body, recordExposure = false))
    }

    // ---- isValidFlagKey (INT-7455) ---------------------------------------------------------

    @Test
    fun `flag keys are letters, digits, underscore and hyphen, at least one`() {
        for (key in listOf("a", "ABC", "a_b-1", "-", "_", "0")) assertTrue(key, isValidFlagKey(key))
        for (key in listOf("", " ", "a.b", "a b", "a/b", " a", "a ", "a\nb", "é")) {
            assertFalse(key, isValidFlagKey(key))
        }
    }
}
