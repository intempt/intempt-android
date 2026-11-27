package com.intempt.core.flags

import com.intempt.core.types.FlagContext
import com.intempt.core.types.FlagError
import com.intempt.core.types.FlagErrorCode
import com.intempt.core.types.FlagErrorKind
import com.intempt.core.types.FlagErrorReporter
import com.intempt.core.types.FlagReason
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The flag evaluator against a fake transport and a hand-driven stream: Node's flag tests
 * (routes, exposure, detail, fail-safe, typed, cache + stream, expose-on-read) ported in spirit.
 */
class FlagEvaluatorTest {
    private class Call(val route: String, val body: Map<String, Any>)

    private class Handle : StreamHandle {
        var closed = false

        override fun close() {
            closed = true
        }
    }

    private val calls = mutableListOf<Call>()
    private val answers = mutableMapOf<String, ArrayDeque<FlagHttpResponse>>()
    private val streams = mutableListOf<Pair<StreamHandlers, Handle>>()
    private val warnings = mutableListOf<String>()
    private val hooked = mutableListOf<FlagError>()
    private val errorLogs = mutableListOf<String>()
    private val deferred = mutableListOf<suspend () -> Unit>()
    private var now = 5_000_000L
    private var sourceId: String? = "src-1"
    private var storedProfile: String? = "prof-1"

    private val reporter =
        FlagErrorReporter({ "src-1" }, { errorLogs += it }, {}).also { r -> r.hook = { hooked += it } }

    private val flags =
        FlagEvaluator(
            post = { route, body ->
                calls += Call(route, body)
                answers[route]?.removeFirstOrNull() ?: FlagHttpResponse(0, null, false)
            },
            identity = { ctx -> FlagIdentity(sourceId, ctx.profileId ?: storedProfile, ctx.userId, "sess-1") },
            openStream = { h -> Handle().also { streams += h to it } },
            schedule = { _, _ -> Cancellable {} },
            launch = { deferred += it },
            reporter = reporter,
            warn = { warnings += it },
            clock = { now },
        )

    private fun answer(
        route: String,
        status: Int,
        body: String?,
    ) {
        answers.getOrPut(route) { ArrayDeque() }.addLast(FlagHttpResponse(status, body, false))
    }

    private fun served(
        key: String,
        value: String,
        extra: String = "",
    ) = """{"key":"$key","value":$value,"variationId":7,"reason":"targeted"$extra}"""

    private fun runExposes() =
        runBlocking {
            val pending = deferred.toList()
            deferred.clear()
            pending.forEach { it() }
        }

    private val ctx = FlagContext()

    // ---- routes ------------------------------------------------------------------------------

    @Test
    fun `a single read records through flags evaluate and returns the served value with its fields`() =
        runBlocking {
            answer("evaluate", 200, served("k", "\"blue\"", ""","valueType":"string","rule":2,"variant":"v2""""))
            val d = flags.detail("k", ctx, "red")
            assertEquals("blue", d.value)
            assertEquals(FlagReason.TARGETED, d.reason)
            assertEquals(7L, d.variationId)
            assertEquals("string", d.valueType)
            assertEquals(2, d.rule)
            assertEquals("v2", d.variant)
            val call = calls.single()
            assertEquals("evaluate", call.route)
            assertEquals("k", call.body["key"])
            assertEquals("sess-1", call.body["sessionId"])
            assertEquals(mapOf("sourceId" to "src-1", "profileId" to "prof-1"), call.body["identification"])
            assertFalse(call.body.containsKey("exposure"))
        }

    @Test
    fun `disableExposure reads flags all with that one key and no session`() =
        runBlocking {
            answer("all", 200, """{"flags":[${served("k", "true")}]}""")
            assertEquals(true, flags.detail("k", ctx, false, disableExposure = true).value)
            val call = calls.single()
            assertEquals("all", call.route)
            assertEquals(listOf("k"), call.body["keys"])
            assertFalse(call.body.containsKey("sessionId"))
        }

    @Test
    fun `a disableExposure read whose key is absent is FLAG_NOT_FOUND`() =
        runBlocking {
            answer("all", 200, """{"flags":[${served("other", "true")}]}""")
            val d = flags.detail("k", ctx, false, disableExposure = true)
            assertEquals(false, d.value)
            assertEquals(FlagReason.ERROR, d.reason)
            assertEquals(FlagErrorCode.FLAG_NOT_FOUND, d.errorCode)
            assertNull(d.error)
        }

    @Test
    fun `no flag path ever calls choose-api`() =
        runBlocking {
            flags.detail("k", ctx, 1.0)
            flags.detail("k", ctx, 1.0, disableExposure = true)
            flags.all(ctx)
            assertEquals(listOf("evaluate", "all", "all"), calls.map { it.route })
        }

    // ---- answers and failures ----------------------------------------------------------------

    @Test
    fun `nothing served returns the default with the server reason`() =
        runBlocking {
            answer("evaluate", 200, """{"key":"k","value":null,"reason":"killed"}""")
            val d = flags.detail("k", ctx, "d")
            assertEquals("d", d.value)
            assertEquals(FlagReason.KILLED, d.reason)
            assertNull(d.errorCode)
        }

    @Test
    fun `404 is FLAG_NOT_FOUND with the error, and fires the hook`() =
        runBlocking {
            answer("evaluate", 404, """{"message":"Flag not found"}""")
            val d = flags.detail("k", ctx, "d")
            assertEquals("d", d.value)
            assertEquals(FlagReason.ERROR, d.reason)
            assertEquals(FlagErrorCode.FLAG_NOT_FOUND, d.errorCode)
            assertEquals(404, d.error!!.status)
            assertEquals(listOf(FlagErrorKind.BAD_REQUEST), hooked.map { it.kind })
        }

    @Test
    fun `500 is GENERAL with a server error`() =
        runBlocking {
            answer("evaluate", 500, "")
            val d = flags.detail("k", ctx, "d")
            assertEquals("d", d.value)
            assertEquals(FlagErrorCode.GENERAL, d.errorCode)
            assertEquals(FlagErrorKind.SERVER, d.error!!.kind)
        }

    @Test
    fun `a 401 is logged at error once naming the source, then quietly`() =
        runBlocking {
            answer("evaluate", 401, "")
            answer("evaluate", 403, "")
            flags.detail("k", ctx, "d")
            flags.detail("k", ctx, "d")
            assertEquals(1, errorLogs.size)
            assertTrue(errorLogs.single().contains("src-1"))
            assertEquals(2, hooked.size)
        }

    @Test
    fun `a parse failure is GENERAL with a parse error`() =
        runBlocking {
            answer("evaluate", 200, "[]")
            val d = flags.detail("k", ctx, "d")
            assertEquals(FlagErrorKind.PARSE, d.error!!.kind)
            assertEquals(FlagErrorCode.GENERAL, d.errorCode)
        }

    @Test
    fun `a wrong-typed value is TYPE_MISMATCH and never coerced`() =
        runBlocking {
            answer("evaluate", 200, served("k", "\"false\"", ""","valueType":"string""""))
            val d = flags.detail("k", ctx, true)
            assertEquals(true, d.value)
            assertEquals(FlagErrorCode.TYPE_MISMATCH, d.errorCode)
        }

    @Test
    fun `a json default gets any JSON value back whole`() =
        runBlocking {
            answer("evaluate", 200, served("k", "[1,2]", ""","valueType":"json""""))
            val d = flags.detail("k", ctx, JsonArray(emptyList()))
            assertEquals(JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2))), d.value)
        }

    // ---- fail-safe (S11) -----------------------------------------------------------------------

    @Test
    fun `an invalid key returns the default as FLAG_NOT_FOUND, sends nothing and warns once per key`() =
        runBlocking {
            for (bad in listOf("", "a b", "a.b", "a b")) {
                val d = flags.detail(bad, ctx, "d")
                assertEquals("d", d.value)
                assertEquals(FlagReason.ERROR, d.reason)
                assertEquals(FlagErrorCode.FLAG_NOT_FOUND, d.errorCode)
            }
            assertTrue(calls.isEmpty())
            assertEquals(3, warnings.size)
            assertTrue(warnings[1].contains("\"a b\""))
            assertTrue(hooked.isEmpty())
        }

    @Test
    fun `no usable identity is TARGETING_KEY_MISSING and sends nothing`() =
        runBlocking {
            sourceId = null
            val d = flags.detail("k", ctx, "d")
            assertEquals("d", d.value)
            assertEquals(FlagReason.ERROR, d.reason)
            assertEquals(FlagErrorCode.TARGETING_KEY_MISSING, d.errorCode)
            flags.detail("k", ctx, "d")
            assertTrue(calls.isEmpty())
            assertEquals(1, warnings.size)
            assertTrue(streams.isEmpty())
            assertTrue(flags.all(ctx).isEmpty())
            assertEquals(2, warnings.size)
            assertTrue(warnings[1].contains("allFlags"))
        }

    @Test
    fun `a userId alone is enough`() =
        runBlocking {
            sourceId = null
            answer("evaluate", 200, served("k", "1"))
            assertEquals(1L, flags.detail("k", FlagContext(userId = "u"), null).value)
            assertEquals(mapOf("profileId" to "prof-1", "userId" to "u"), calls.single().body["identification"])
        }

    // ---- cache + stream (F15) ------------------------------------------------------------------

    private fun connect() {
        streams.last().first.onOpen()
    }

    @Test
    fun `a read opens one stream and repeat reads answer from the cache while it is connected`() =
        runBlocking {
            answer("evaluate", 200, served("k", "true"))
            flags.detail("k", ctx, false)
            assertEquals(1, streams.size)
            connect()
            answer("evaluate", 200, served("k", "true"))
            flags.detail("k", ctx, false)
            assertEquals(2, calls.size)
            assertEquals(true, flags.detail("k", ctx, false).value)
            assertEquals(true, flags.detail("k", ctx, false).value)
            assertEquals(2, calls.size)
            assertEquals(1, streams.size)
            now += FRESH_TTL_MS + 1
            answer("evaluate", 200, served("k", "false"))
            assertEquals(false, flags.detail("k", ctx, true).value)
            assertEquals(3, calls.size)
        }

    @Test
    fun `a changed key is dropped at once and the next read asks the server`() =
        runBlocking {
            flags.detail("x", ctx, false)
            connect()
            answer("evaluate", 200, served("k", "true"))
            answer("evaluate", 200, served("j", "true"))
            flags.detail("k", ctx, false)
            flags.detail("j", ctx, false)
            streams.last().first.onMessage("flags_changed", """{"reason":"kill","keys":["k"]}""")
            answer("evaluate", 200, """{"key":"k","value":null,"reason":"killed"}""")
            val killed = flags.detail("k", ctx, false)
            assertEquals(false, killed.value)
            assertEquals(FlagReason.KILLED, killed.reason)
            assertEquals(true, flags.detail("j", ctx, false).value)
            assertEquals(4, calls.size)
            streams.last().first.onMessage("flags_changed", """{"keys":[]}""")
            flags.detail("j", ctx, false)
            assertEquals(5, calls.size)
        }

    @Test
    fun `without a stream every read asks, and a failure falls back to the last value as CACHED`() =
        runBlocking {
            answer("evaluate", 200, served("k", "true"))
            flags.detail("k", ctx, false)
            answer("evaluate", 503, "")
            val d = flags.detail("k", ctx, false)
            assertEquals(true, d.value)
            assertEquals(FlagReason.CACHED, d.reason)
            assertNull(d.error)
            assertEquals(1, hooked.size)
            now += CACHE_TTL_MS + 1
            answer("evaluate", 503, "")
            assertEquals(false, flags.detail("k", ctx, false).value)
        }

    @Test
    fun `an auth failure, a 404 or a served null never falls back to the cache`() =
        runBlocking {
            answer("evaluate", 200, served("k", "true"))
            flags.detail("k", ctx, false)
            answer("evaluate", 401, "")
            assertEquals(false, flags.detail("k", ctx, false).value)
            answer("evaluate", 404, "")
            assertEquals(false, flags.detail("k", ctx, false).value)
            answer("evaluate", 0, null)
            assertEquals(FlagReason.ERROR, flags.detail("k", ctx, false).reason)

            answer("evaluate", 200, served("j", "true"))
            flags.detail("j", ctx, false)
            answer("evaluate", 200, """{"key":"j","value":null,"reason":"off"}""")
            flags.detail("j", ctx, false)
            answer("evaluate", 0, null)
            assertEquals(FlagReason.ERROR, flags.detail("j", ctx, false).reason)
        }

    @Test
    fun `a stream event that lands while a read is in flight keeps that answer from being fresh`() =
        runBlocking {
            val opened = mutableListOf<StreamHandlers>()
            var onPost: () -> Unit = {}
            val slow =
                FlagEvaluator(
                    post = { route, body ->
                        calls += Call(route, body)
                        onPost()
                        FlagHttpResponse(200, served("k", "true"), false)
                    },
                    identity = { FlagIdentity("s", "p", null, null) },
                    openStream = { h -> Handle().also { opened += h } },
                    schedule = { _, _ -> Cancellable {} },
                    launch = {},
                    reporter = reporter,
                    warn = {},
                    clock = { now },
                )
            slow.detail("x", ctx, false)
            opened.single().onOpen()
            var inFlight = -1
            onPost = {
                inFlight = slow.pendingReads
                opened.single().onMessage("flags_changed", """{"keys":["k"]}""")
            }
            slow.detail("k", ctx, false)
            assertEquals(1, inFlight)
            assertEquals(0, slow.pendingReads)
            onPost = {}
            slow.detail("k", ctx, false)
            slow.detail("k", ctx, false)
            assertEquals(3, calls.size)
        }

    @Test
    fun `pause closes the stream and the next read reopens it`() =
        runBlocking {
            flags.detail("k", ctx, false)
            connect()
            flags.pause()
            assertTrue(streams.last().second.closed)
            flags.detail("k", ctx, false)
            assertEquals(2, streams.size)
        }

    @Test
    fun `close ends the stream for good and sends no exposure`() =
        runBlocking {
            flags.detail("k", ctx, false)
            flags.close()
            assertTrue(streams.single().second.closed)
            flags.detail("k", ctx, false)
            assertEquals(1, streams.size)
            answer("all", 200, """{"flags":[${served("a", "1")}]}""")
            flags.all(ctx)["a"]
            assertTrue(deferred.isEmpty())
        }

    @Test
    fun `forget clears cached answers`() =
        runBlocking {
            answer("evaluate", 200, served("k", "true"))
            flags.detail("k", ctx, false)
            flags.forget()
            answer("evaluate", 503, "")
            assertEquals(FlagReason.ERROR, flags.detail("k", ctx, false).reason)
        }

    // ---- allFlags + expose on read (F32) -------------------------------------------------------

    @Test
    fun `allFlags maps every keyed entry, nothing served to null, and records nothing`() =
        runBlocking {
            answer(
                "all",
                200,
                """{"flags":[${served("a", "\"x\"")},{"key":"b","value":null,"reason":"off"},{"value":1},1]}""",
            )
            val all = flags.all(ctx)
            assertEquals(setOf("a", "b"), all.keys)
            assertEquals(2, all.size)
            assertTrue(all.containsKey("b"))
            assertTrue(deferred.isEmpty())
            val call = calls.single()
            assertEquals("all", call.route)
            assertFalse(call.body.containsKey("keys"))
            assertFalse(call.body.containsKey("sessionId"))
            assertTrue(streams.isEmpty())
        }

    @Test
    fun `reading one of several prefetched flags records one exposure, once`() =
        runBlocking {
            answer("all", 200, """{"flags":[${served("a", "1")},${served("b", "2")},{"key":"c","value":3}]}""")
            answer("expose", 202, "")
            val all = flags.all(ctx)
            assertEquals(1L, all["a"])
            assertEquals(1L, all["a"])
            assertEquals(3L, all["c"])
            assertNull(all["zzz"])
            runExposes()
            val exposes = calls.filter { it.route == "expose" }
            assertEquals(1, exposes.size)
            assertEquals("a", exposes.single().body["key"])
            assertEquals(7L, exposes.single().body["variationId"])
            assertEquals("sess-1", exposes.single().body["sessionId"])
            assertEquals("mobile", exposes.single().body["device"])
        }

    @Test
    fun `a new allFlags map records again, and iterating values exposes every served key`() =
        runBlocking {
            answer("all", 200, """{"flags":[${served("a", "1")},${served("b", "2")}]}""")
            answer("all", 200, """{"flags":[${served("a", "1")},${served("b", "2")}]}""")
            flags.all(ctx)["a"]
            val second = flags.all(ctx)
            assertEquals(listOf(1L, 2L), second.values.toList())
            assertEquals(2, second.entries.size)
            second["b"]
            runExposes()
            assertEquals(listOf("a", "a", "b"), calls.filter { it.route == "expose" }.map { it.body["key"] })
        }

    @Test
    fun `a failed exposure warns once per kind and never reaches the hook`() =
        runBlocking {
            answer("all", 200, """{"flags":[${served("a", "1")},${served("b", "2")}]}""")
            answer("expose", 500, "")
            answer("expose", 503, "")
            val all = flags.all(ctx)
            all["a"]
            all["b"]
            runExposes()
            assertEquals(1, warnings.size)
            assertTrue(warnings.single().contains("\"a\""))
            assertTrue(hooked.isEmpty())
        }

    @Test
    fun `an allFlags failure is empty and reported with no key`() =
        runBlocking {
            answer("all", 500, "")
            assertTrue(flags.all(ctx).isEmpty())
            assertNull(hooked.single().key)
            assertEquals(FlagErrorKind.SERVER, hooked.single().kind)
        }

    @Test
    fun `the exposing map reads like a plain map`() =
        runBlocking {
            answer("all", 200, """{"flags":[${served("a", "{\"x\":1}")}]}""")
            val all = flags.all(ctx)
            assertTrue(all.containsValue(JsonObject(mapOf("x" to JsonPrimitive(1)))))
            assertFalse(all.isEmpty())
            assertEquals(all, mapOf("a" to JsonObject(mapOf("x" to JsonPrimitive(1)))))
            assertEquals(mapOf("a" to JsonObject(mapOf("x" to JsonPrimitive(1)))).hashCode(), all.hashCode())
            assertTrue(all.toString().contains("a="))
        }

    @Test
    fun `an exposing map differs from a map with other contents`() {
        val m: Map<String, Any?> = ExposingFlagMap(mapOf("a" to 1)) {}
        assertFalse(m == mapOf("a" to 2))
        assertEquals(mapOf("a" to 1), m)
    }

    @Test
    fun `an entry with an empty key is skipped`() =
        runBlocking {
            answer("all", 200, """{"flags":[{"key":"","value":1,"variationId":1},${served("a", "1")}]}""")
            assertEquals(setOf("a"), flags.all(ctx).keys)
        }

    @Test
    fun `every read leaves nothing pending, failed or not`() =
        runBlocking {
            answer("evaluate", 200, served("k", "1"))
            flags.detail("k", ctx, 0.0)
            flags.detail("k", ctx, 0.0)
            flags.detail("k", ctx, 0.0, disableExposure = true)
            assertEquals(0, flags.pendingReads)
        }

    @Test
    fun `a disableExposure read that finds the flag gone forgets the cached value`() =
        runBlocking {
            answer("evaluate", 200, served("k", "true"))
            flags.detail("k", ctx, false)
            answer("all", 200, """{"flags":[]}""")
            assertEquals(FlagErrorCode.FLAG_NOT_FOUND, flags.detail("k", ctx, false, disableExposure = true).errorCode)
            answer("evaluate", 503, "")
            assertEquals(FlagReason.ERROR, flags.detail("k", ctx, false).reason)
        }

    @Test
    fun `an auth failure keeps the cached value for a later outage`() =
        runBlocking {
            answer("evaluate", 200, served("k", "true"))
            flags.detail("k", ctx, false)
            answer("evaluate", 401, "")
            assertEquals(FlagErrorCode.GENERAL, flags.detail("k", ctx, false).errorCode)
            answer("evaluate", 503, "")
            assertEquals(FlagReason.CACHED, flags.detail("k", ctx, false).reason)
        }

    @Test
    fun `a disableExposure failure is reported to the hook`() =
        runBlocking {
            answer("all", 500, "")
            val d = flags.detail("k", ctx, "d", disableExposure = true)
            assertEquals(FlagErrorCode.GENERAL, d.errorCode)
            assertEquals("k", hooked.single().key)
        }

    @Test
    fun `an answer cached before a reconnect is not fresh after it`() =
        runBlocking {
            flags.detail("x", ctx, false)
            connect()
            answer("evaluate", 200, served("k", "true"))
            answer("evaluate", 200, served("k", "true"))
            flags.detail("k", ctx, false)
            streams.last().first.onClose()
            now += 1
            streams.last().first.onOpen()
            flags.detail("k", ctx, false)
            assertEquals(3, calls.size)
        }

    @Test
    fun `a reconnect while a read is in flight keeps that answer from being fresh`() =
        runBlocking {
            val opened = mutableListOf<StreamHandlers>()
            var onPost: () -> Unit = {}
            val slow =
                FlagEvaluator(
                    post = { route, body ->
                        calls += Call(route, body)
                        onPost()
                        FlagHttpResponse(200, served("k", "true"), false)
                    },
                    identity = { FlagIdentity("s", "p", null, null) },
                    openStream = { h -> Handle().also { opened += h } },
                    schedule = { _, _ -> Cancellable {} },
                    launch = {},
                    reporter = reporter,
                    warn = {},
                    clock = { now },
                )
            slow.detail("x", ctx, false)
            opened.single().onOpen()
            onPost = {
                opened.single().onClose()
                opened.single().onOpen()
            }
            slow.detail("k", ctx, false)
            onPost = {}
            slow.detail("k", ctx, false)
            assertEquals(3, calls.size)
        }
}
