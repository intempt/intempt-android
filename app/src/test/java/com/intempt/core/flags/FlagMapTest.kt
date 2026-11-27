package com.intempt.core.flags

import com.intempt.core.types.FlagContext
import com.intempt.core.types.FlagErrorReporter
import com.intempt.core.types.FlagMap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `allFlags` reads a bridge needs (PLAN §7 item 5, Beso option A): `peek`/`snapshot` record
 * nothing, `expose` records once per map and shares that once with `get`. `getOrDefault` is API 24,
 * so it is covered under Robolectric in `FlagExposureTest`.
 */
class FlagMapTest {
    private class Call(val route: String, val body: Map<String, Any>)

    private val calls = mutableListOf<Call>()
    private val answers = mutableMapOf<String, ArrayDeque<FlagHttpResponse>>()
    private val deferred = mutableListOf<suspend () -> Unit>()

    private val flags =
        FlagEvaluator(
            post = { route, body ->
                calls += Call(route, body)
                answers[route]?.removeFirstOrNull() ?: FlagHttpResponse(0, null, false)
            },
            identity = { _ -> FlagIdentity("src-1", "prof-1", null, "sess-1") },
            openStream = { _ -> StreamHandle {} },
            schedule = { _, _ -> Cancellable {} },
            launch = { deferred += it },
            reporter = FlagErrorReporter({ "src-1" }, {}, {}),
            warn = {},
            clock = { 0L },
        )

    private val ctx = FlagContext()

    private fun answer(
        route: String,
        status: Int,
        body: String,
    ) {
        answers.getOrPut(route) { ArrayDeque() }.addLast(FlagHttpResponse(status, body, false))
    }

    private fun served(
        key: String,
        value: String,
    ) = """{"key":"$key","value":$value,"variationId":7,"reason":"targeted"}"""

    private fun exposed(): List<Any?> =
        runBlocking {
            val pending = deferred.toList()
            deferred.clear()
            pending.forEach { it() }
            calls.filter { it.route == "expose" }.map { it.body["key"] }
        }

    @Test
    fun `peek, snapshot and the diagnostic reads record nothing`() =
        runBlocking {
            answer("all", 200, """{"flags":[${served("a", "1")},${served("b", "2")}]}""")
            val all: FlagMap = flags.all(ctx)
            assertEquals(1L, all.peek("a"))
            assertNull(all.peek("zzz"))
            val snapshot = all.snapshot()
            assertEquals(mapOf("a" to 1L, "b" to 2L), snapshot)
            assertEquals(listOf(1L, 2L), snapshot.values.toList())
            assertEquals(2, snapshot.entries.size)
            assertEquals(2L, snapshot["b"])
            assertFalse(snapshot is FlagMap)
            assertEquals(all, mapOf("a" to 1L, "b" to 2L))
            all.hashCode()
            all.toString()
            assertTrue(exposed().isEmpty())
        }

    @Test
    fun `a snapshot is a fresh copy each time`() =
        runBlocking {
            answer("all", 200, """{"flags":[${served("a", "1")}]}""")
            val all = flags.all(ctx)
            val snapshot = all.snapshot()
            assertNotSame(snapshot, all.snapshot())
            assertEquals(all.snapshot(), snapshot)
        }

    @Test
    fun `expose records a served key once per map, shared with get, and nothing for an unserved key`() =
        runBlocking {
            answer(
                "all",
                200,
                """{"flags":[${served("a", "1")},${served("b", "2")},""" +
                    """{"key":"c","value":3},{"key":"d","value":null}]}""",
            )
            answer("expose", 202, "")
            answer("expose", 202, "")
            val all = flags.all(ctx)
            all.expose("a")
            all.expose("a")
            all["a"]
            all["b"]
            all.expose("b")
            all.expose("c")
            all.expose("d")
            all.expose("zzz")
            assertEquals(listOf("a", "b"), exposed())
            assertEquals(7L, calls.first { it.route == "expose" }.body["variationId"])
        }

    @Test
    fun `a new map exposes again`() =
        runBlocking {
            answer("all", 200, """{"flags":[${served("a", "1")}]}""")
            answer("all", 200, """{"flags":[${served("a", "1")}]}""")
            flags.all(ctx).expose("a")
            flags.all(ctx).expose("a")
            assertEquals(listOf("a", "a"), exposed())
        }

    @Test
    fun `an answer with nothing to expose still offers peek, snapshot and a no-op expose`() =
        runBlocking {
            answer("all", 200, """{"flags":[{"key":"c","value":3}]}""")
            val all = flags.all(ctx)
            assertEquals(3L, all.peek("c"))
            assertEquals(mapOf("c" to 3L), all.snapshot())
            all.expose("c")
            assertTrue(exposed().isEmpty())
        }

    @Test
    fun `the empty map reads as empty and exposes nothing`() {
        val empty = emptyFlagMap()
        assertTrue(empty.isEmpty())
        assertTrue(empty.snapshot().isEmpty())
        assertNull(empty.peek("a"))
        empty.expose("a")
        assertEquals(emptyMap<String, Any?>(), empty)
    }

    @Test
    fun `expose hands the key to the map's recorder`() {
        val seen = mutableListOf<String>()
        val map = ExposingFlagMap(mapOf("a" to 1)) { seen += it }
        map.expose("a")
        map.peek("a")
        map.snapshot()
        assertEquals(listOf("a"), seen)
        assertSame(1, map.peek("a"))
    }
}
