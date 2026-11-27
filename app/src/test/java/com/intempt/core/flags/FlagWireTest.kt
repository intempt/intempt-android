package com.intempt.core.flags

import com.intempt.core.types.FlagErrorCode
import com.intempt.core.types.FlagErrorKind
import com.intempt.core.types.FlagReason
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The flag module's pure wire decisions (PLAN §3; shapes from audience-service `payload/flag`). */
class FlagWireTest {
    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    private fun flag(json: String): RawFlag = rawFlagOf(obj(json))

    private val profile = FlagIdentity("src-1", "prof-1", null, "sess-1")

    // ---- identity and request bodies -------------------------------------------------------

    @Test
    fun `a context is answerable with a userId, or a profileId with a sourceId`() {
        assertTrue(FlagIdentity(null, null, "u", null).isAnswerable())
        assertTrue(FlagIdentity("s", "p", null, null).isAnswerable())
        assertFalse(FlagIdentity("s", null, null, null).isAnswerable())
        assertFalse(FlagIdentity(null, "p", null, null).isAnswerable())
        assertFalse(FlagIdentity(" ", "p", null, null).isAnswerable())
        assertFalse(FlagIdentity("s", " ", " ", null).isAnswerable())
        assertFalse(FlagIdentity(null, null, null, "sess").isAnswerable())
    }

    @Test
    fun `an identity keeps what it was given`() {
        val who = FlagIdentity("s", "p", "u", "x")
        assertEquals("s", who.sourceId)
        assertEquals("p", who.profileId)
        assertEquals("u", who.userId)
        assertEquals("x", who.sessionId)
    }

    @Test
    fun `evaluate sends identification, device, key and sessionId`() {
        val body = evaluateBody(FlagIdentity("src-1", "prof-1", "user-1", "sess-1"), "checkout")
        assertEquals(
            mapOf("sourceId" to "src-1", "profileId" to "prof-1", "userId" to "user-1"),
            body["identification"],
        )
        assertEquals("mobile", body["device"])
        assertEquals("checkout", body["key"])
        assertEquals("sess-1", body["sessionId"])
        assertEquals(4, body.size)
    }

    @Test
    fun `blank identifiers and a blank session are omitted, not sent empty`() {
        val body = evaluateBody(FlagIdentity("src-1", " ", null, ""), "k")
        assertEquals(mapOf("sourceId" to "src-1"), body["identification"])
        assertFalse(body.containsKey("sessionId"))
    }

    @Test
    fun `all sends no sessionId, and keys only when given`() {
        val every = allBody(profile, null)
        assertFalse(every.containsKey("keys"))
        assertFalse(every.containsKey("sessionId"))
        assertEquals("mobile", every["device"])
        assertEquals(mapOf("sourceId" to "src-1", "profileId" to "prof-1"), every["identification"])
        assertEquals(listOf("k"), allBody(profile, listOf("k"))["keys"])
    }

    @Test
    fun `expose sends key, variationId, device and sessionId`() {
        val body = exposeBody(profile, "k", 77L)
        assertEquals("k", body["key"])
        assertEquals(77L, body["variationId"])
        assertEquals("mobile", body["device"])
        assertEquals("sess-1", body["sessionId"])
        assertEquals(mapOf("sourceId" to "src-1", "profileId" to "prof-1"), body["identification"])
    }

    @Test
    fun `the cache key follows the bucketing identity and cannot collide`() {
        val byProfile = FlagIdentity("s", "p", "u1", null).cacheKey("k")
        assertEquals(byProfile, FlagIdentity("s", "p", "u2", null).cacheKey("k"))
        assertNotEquals(byProfile, FlagIdentity("s", "p", null, null).cacheKey("k2"))
        assertNotEquals(byProfile, FlagIdentity("s2", "p", null, null).cacheKey("k"))
        val byUser = FlagIdentity(null, null, "u1", null).cacheKey("k")
        assertNotEquals(byUser, FlagIdentity(null, null, "u2", null).cacheKey("k"))
        assertNotEquals(byUser, FlagIdentity("s", " ", "u1", null).cacheKey("k").replace("u1", "x"))
        assertEquals(byUser, FlagIdentity("s", " ", "u1", null).cacheKey("k"))
        assertEquals(byUser, FlagIdentity(" ", "p", "u1", null).cacheKey("k"))
        assertNotEquals(
            FlagIdentity(null, null, "a\",\"b", null).cacheKey("k"),
            FlagIdentity(null, null, "a", null).cacheKey("k\",\"b"),
        )
    }

    // ---- FlagValue parsing ------------------------------------------------------------------

    @Test
    fun `a served FlagValue keeps every field`() {
        val f =
            flag(
                """{"key":"k","value":true,"variationId":12,"valueType":"boolean",
                |"reason":"targeted","rule":2,"variant":"on"}
                """.trimMargin(),
            )
        assertEquals("k", f.key)
        assertEquals(JsonPrimitive(true), f.value)
        assertTrue(f.hasValue)
        assertEquals(12L, f.variationId)
        assertEquals("boolean", f.valueType)
        assertEquals("targeted", f.reason)
        assertEquals(2, f.rule)
        assertEquals("on", f.variant)
    }

    @Test
    fun `fields of the wrong type are dropped, never thrown`() {
        val f =
            flag(
                """{"key":5,"variationId":"12","valueType":1,"reason":{},"rule":"2","variant":[],
                |"value":null}
                """.trimMargin(),
            )
        assertNull(f.key)
        assertNull(f.variationId)
        assertNull(f.valueType)
        assertNull(f.reason)
        assertNull(f.rule)
        assertNull(f.variant)
        assertFalse(f.hasValue)
        assertFalse(flag("""{"key":"k"}""").hasValue)
        assertNull(flag("""{"key":"k","variationId":true,"rule":false}""").variationId)
        assertNull(flag("""{"key":"k","rule":false}""").rule)
    }

    @Test
    fun `an empty reason reads as none`() {
        assertNull(flag("""{"key":"k","reason":""}""").reason)
    }

    // ---- outcomes ---------------------------------------------------------------------------

    @Test
    fun `evaluate classifies transport failures with the key`() {
        val timeout = evaluateOutcomeOf("k", FlagHttpResponse(0, null, true)).error!!
        assertEquals(FlagErrorKind.TIMEOUT, timeout.kind)
        assertNull(timeout.status)
        assertEquals("k", timeout.key)
        assertEquals(FlagErrorKind.NETWORK, evaluateOutcomeOf("k", FlagHttpResponse(0, null, false)).error!!.kind)
        val notFound = evaluateOutcomeOf("k", FlagHttpResponse(404, """{"key":"k"}""", false))
        assertNull(notFound.flag)
        assertEquals(404, notFound.error!!.status)
        assertEquals(FlagErrorKind.BAD_REQUEST, notFound.error!!.kind)
        assertEquals(FlagErrorKind.AUTH, evaluateOutcomeOf("k", FlagHttpResponse(401, "", false)).error!!.kind)
    }

    @Test
    fun `evaluate answers an object and calls anything else a parse error`() {
        val ok = evaluateOutcomeOf("k", FlagHttpResponse(200, """{"key":"k","value":1}""", false))
        assertNull(ok.error)
        assertEquals("k", ok.flag!!.key)
        for (body in listOf("[]", "nope", null, "1")) {
            val bad = evaluateOutcomeOf("k", FlagHttpResponse(200, body, false))
            assertNull(bad.flag)
            assertEquals(FlagErrorKind.PARSE, bad.error!!.kind)
            assertEquals(200, bad.error!!.status)
        }
        assertNull(evaluateOutcomeOf("k", FlagHttpResponse(299, "{}", false)).error)
        assertEquals(FlagErrorKind.BAD_REQUEST, evaluateOutcomeOf("k", FlagHttpResponse(300, "{}", false)).error!!.kind)
        assertEquals(FlagErrorKind.BAD_REQUEST, evaluateOutcomeOf("k", FlagHttpResponse(199, "{}", false)).error!!.kind)
    }

    @Test
    fun `all keeps the object entries of a flags array`() {
        val ok = allOutcomeOf(null, FlagHttpResponse(200, """{"flags":[{"key":"a"},1,null,{"key":"b"}]}""", false))
        assertNull(ok.error)
        assertEquals(listOf("a", "b"), ok.flags.map { it.key })
        for (body in listOf("""{"flags":{}}""", "[]", "x", null)) {
            val bad = allOutcomeOf("k", FlagHttpResponse(200, body, false))
            assertTrue(bad.flags.isEmpty())
            assertEquals(FlagErrorKind.PARSE, bad.error!!.kind)
            assertEquals("k", bad.error!!.key)
        }
        val failed = allOutcomeOf(null, FlagHttpResponse(503, """{"flags":[{"key":"a"}]}""", false))
        assertTrue(failed.flags.isEmpty())
        assertEquals(FlagErrorKind.SERVER, failed.error!!.kind)
        assertNull(failed.error!!.key)
    }

    // ---- typed reads (F30) ------------------------------------------------------------------

    @Test
    fun `the default names the expected type`() {
        assertEquals(FlagValueType.BOOLEAN, expectedTypeOf(false))
        assertEquals(FlagValueType.STRING, expectedTypeOf(""))
        assertEquals(FlagValueType.NUMBER, expectedTypeOf(1.0))
        assertEquals(FlagValueType.NUMBER, expectedTypeOf(1))
        assertEquals(FlagValueType.JSON, expectedTypeOf(JsonObject(emptyMap())))
        assertEquals(FlagValueType.JSON, expectedTypeOf(JsonArray(emptyList())))
        assertEquals(FlagValueType.JSON, expectedTypeOf(mapOf("a" to 1)))
        assertNull(expectedTypeOf(null))
        assertEquals(listOf("boolean", "string", "number", "json"), FlagValueType.entries.map { it.wire })
    }

    @Test
    fun `values match their JSON type and a declared valueType must agree`() {
        assertTrue(matchesType(FlagValueType.BOOLEAN, JsonPrimitive(true), null))
        assertFalse(matchesType(FlagValueType.BOOLEAN, JsonPrimitive("true"), null))
        assertFalse(matchesType(FlagValueType.BOOLEAN, JsonPrimitive(1), null))
        assertTrue(matchesType(FlagValueType.STRING, JsonPrimitive("x"), null))
        assertFalse(matchesType(FlagValueType.STRING, JsonPrimitive(1), null))
        assertFalse(matchesType(FlagValueType.STRING, JsonObject(emptyMap()), null))
        assertTrue(matchesType(FlagValueType.NUMBER, JsonPrimitive(1.5), null))
        assertFalse(matchesType(FlagValueType.NUMBER, JsonPrimitive("1"), null))
        assertFalse(matchesType(FlagValueType.NUMBER, JsonPrimitive(true), null))
        assertFalse(matchesType(FlagValueType.NUMBER, JsonArray(emptyList()), null))
        assertTrue(matchesType(FlagValueType.JSON, JsonPrimitive(3), null))
        assertTrue(matchesType(FlagValueType.JSON, JsonArray(emptyList()), "json"))
        assertFalse(matchesType(FlagValueType.JSON, JsonArray(emptyList()), "string"))
        assertFalse(matchesType(FlagValueType.BOOLEAN, JsonPrimitive(true), "string"))
        assertTrue(matchesType(FlagValueType.BOOLEAN, JsonPrimitive(true), "boolean"))
    }

    @Test
    fun `a served value of the expected type comes back with every answer field`() {
        val served =
            flag(
                """{"key":"k","value":"blue","variationId":3,"valueType":"string","reason":"targeted",
                |"rule":1,"variant":"v"}
                """.trimMargin(),
            )
        val d = typedDetail(served, "red")
        assertEquals("blue", d.value)
        assertEquals(FlagReason.TARGETED, d.reason)
        assertNull(d.errorCode)
        assertNull(d.error)
        assertEquals(3L, d.variationId)
        assertEquals("string", d.valueType)
        assertEquals(1, d.rule)
        assertEquals("v", d.variant)
    }

    @Test
    fun `a wrong type is TYPE_MISMATCH with the default, never a coercion`() {
        val d = typedDetail(flag("""{"key":"k","value":"false","valueType":"string","reason":"targeted"}"""), true)
        assertEquals(true, d.value)
        assertEquals(FlagReason.ERROR, d.reason)
        assertEquals(FlagErrorCode.TYPE_MISMATCH, d.errorCode)
        assertEquals("string", d.valueType)
        assertNull(d.error)
    }

    @Test
    fun `nothing served keeps the server reason and the default`() {
        val d = typedDetail(flag("""{"key":"k","value":null,"reason":"killed","valueType":"boolean"}"""), false)
        assertEquals(false, d.value)
        assertEquals(FlagReason.KILLED, d.reason)
        assertNull(d.errorCode)
        assertEquals(FlagReason.OFF, typedDetail(flag("""{"key":"k"}"""), 1.0).reason)
    }

    @Test
    fun `a null default accepts any type and a json default gets the raw element`() {
        assertEquals(5L, typedDetail(flag("""{"key":"k","value":5}"""), null).value)
        assertEquals(JsonPrimitive(5), typedDetail(flag("""{"key":"k","value":5}"""), JsonNull).value)
        val arr = typedDetail(flag("""{"key":"k","value":[1,2],"valueType":"json"}"""), JsonArray(emptyList()))
        assertEquals(Json.parseToJsonElement("[1,2]"), arr.value)
        assertEquals(1.5, typedDetail(flag("""{"key":"k","value":1.5}"""), 0.0).value)
    }

    // Beso ruling 2026-11-27: jsonVariation types by the default's JSON kind, as Node's expectedType.
    @Test
    fun `a json scalar default names its own type and a JsonNull default names none`() {
        assertEquals(FlagValueType.BOOLEAN, expectedTypeOf(JsonPrimitive(false)))
        assertEquals(FlagValueType.STRING, expectedTypeOf(JsonPrimitive("")))
        assertEquals(FlagValueType.STRING, expectedTypeOf(JsonPrimitive("true")))
        assertEquals(FlagValueType.NUMBER, expectedTypeOf(JsonPrimitive(0)))
        assertEquals(FlagValueType.NUMBER, expectedTypeOf(JsonPrimitive(1.5)))
        assertNull(expectedTypeOf(JsonNull))
    }

    @Test
    fun `a json scalar default gets a served value of its kind back as the raw element`() {
        assertEquals(JsonPrimitive(true), typedDetail(flag("""{"key":"k","value":true}"""), JsonPrimitive(false)).value)
        assertEquals(JsonPrimitive("b"), typedDetail(flag("""{"key":"k","value":"b"}"""), JsonPrimitive("a")).value)
        assertEquals(JsonPrimitive(5), typedDetail(flag("""{"key":"k","value":5}"""), JsonPrimitive(0)).value)
    }

    @Test
    fun `a json scalar default rejects any other kind as TYPE_MISMATCH with the default`() {
        val fallback = JsonPrimitive(false)
        listOf(
            """{"key":"k","value":"false"}""",
            """{"key":"k","value":0}""",
            """{"key":"k","value":{"a":1}}""",
            """{"key":"k","value":[true]}""",
            """{"key":"k","value":true,"valueType":"json"}""",
        ).forEach { json ->
            val d = typedDetail(flag(json), fallback)
            assertEquals(json, fallback, d.value)
            assertEquals(json, FlagReason.ERROR, d.reason)
            assertEquals(json, FlagErrorCode.TYPE_MISMATCH, d.errorCode)
        }
        assertEquals(JsonPrimitive(0), typedDetail(flag("""{"key":"k","value":"0"}"""), JsonPrimitive(0)).value)
        assertEquals(JsonPrimitive("a"), typedDetail(flag("""{"key":"k","value":1}"""), JsonPrimitive("a")).value)
    }

    @Test
    fun `a JsonNull default checks nothing and gets the raw element`() {
        val d = typedDetail(flag("""{"key":"k","value":"x","valueType":"string","reason":"targeted"}"""), JsonNull)
        assertEquals(JsonPrimitive("x"), d.value)
        assertEquals(FlagReason.TARGETED, d.reason)
        assertNull(d.errorCode)
        assertEquals(JsonNull, typedDetail(flag("""{"key":"k","value":null}"""), JsonNull).value)
    }

    @Test
    fun `a reason override marks a cached answer`() {
        val d = typedDetail(flag("""{"key":"k","value":true,"reason":"targeted"}"""), false, FlagReason.CACHED)
        assertEquals(true, d.value)
        assertEquals(FlagReason.CACHED, d.reason)
    }

    // ---- stream -----------------------------------------------------------------------------

    @Test
    fun `a flags_changed event names its keys and anything malformed drops everything`() {
        assertEquals(listOf("a", "b"), keysOfFlagsChanged("""{"reason":"kill","keys":["a","b"]}"""))
        assertEquals(emptyList<String>(), keysOfFlagsChanged("""{"keys":[]}"""))
        assertEquals(emptyList<String>(), keysOfFlagsChanged("""{"keys":["a",1]}"""))
        assertEquals(emptyList<String>(), keysOfFlagsChanged("""{"keys":"a"}"""))
        assertEquals(emptyList<String>(), keysOfFlagsChanged("""[1]"""))
        assertEquals(emptyList<String>(), keysOfFlagsChanged("not json"))
    }

    @Test
    fun `the SSE parser dispatches on a blank line and ignores comments`() {
        val p = SseParser()
        assertNull(p.feed(":keepalive"))
        assertNull(p.feed(""))
        assertNull(p.feed("event: flags_changed"))
        assertNull(p.feed("data: {\"keys\":"))
        assertNull(p.feed("data:[\"a\"]}"))
        val e = p.feed("")!!
        assertEquals("flags_changed", e.event)
        assertEquals("{\"keys\":\n[\"a\"]}", e.data)
        assertNull(p.feed("id: 4"))
        assertNull(p.feed("data: x"))
        val second = p.feed("")!!
        assertEquals("message", second.event)
        assertEquals("x", second.data)
        assertNull(p.feed("event:other"))
        assertNull(p.feed(""))
        assertNull(p.feed("data"))
        assertEquals("", p.feed("")!!.data)
    }

    // ---- warnings ---------------------------------------------------------------------------

    @Test
    fun `a warning is logged once per id and the set is bounded`() {
        val seen = mutableListOf<String>()
        val w = WarnOnce({ seen += it }, max = 2)
        w.warn("a", "A")
        w.warn("a", "A again")
        w.warn("b", "B")
        assertEquals(listOf("A", "B"), seen)
        w.warn("c", "C")
        w.warn("a", "A after reset")
        assertEquals(listOf("A", "B", "C", "A after reset"), seen)
    }
}
