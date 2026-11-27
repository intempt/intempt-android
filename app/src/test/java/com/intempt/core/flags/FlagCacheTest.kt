package com.intempt.core.flags

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** F15: 60 s fresh while the stream is connected, 24 h fallback, 10,000 entries, oldest dropped. */
class FlagCacheTest {
    private var now = 1_000_000L
    private val cache = FlagCache({ now }, max = 3)

    private fun f(key: String): RawFlag {
        val obj = Json.parseToJsonElement("""{"key":"$key","value":1}""") as JsonObject
        return rawFlagOf(obj)
    }

    @Test
    fun `the constants are the contract's`() {
        assertEquals(24 * 60 * 60 * 1000L, CACHE_TTL_MS)
        assertEquals(60 * 1000L, FRESH_TTL_MS)
        assertEquals(10_000, CACHE_MAX)
    }

    @Test
    fun `a trusted entry is fresh only while connected, after the connection, and for 60 s`() {
        val flag = f("a")
        cache.remember("id", "a", flag, started = now, trusted = true)
        assertSame(flag, cache.fresh("id", connected = true, connectedAt = now))
        assertNull(cache.fresh("id", connected = false, connectedAt = now))
        assertNull(cache.fresh("id", connected = true, connectedAt = now + 1))
        assertNull(cache.fresh("other", connected = true, connectedAt = now))
        now += FRESH_TTL_MS
        assertSame(flag, cache.fresh("id", connected = true, connectedAt = 0))
        now += 1
        assertNull(cache.fresh("id", connected = true, connectedAt = 0))
    }

    @Test
    fun `an untrusted entry is never fresh but still recalls`() {
        val flag = f("a")
        cache.remember("id", "a", flag, started = now, trusted = false)
        assertNull(cache.fresh("id", connected = true, connectedAt = 0))
        assertSame(flag, cache.recall("id"))
    }

    @Test
    fun `recall serves for 24 h and then forgets`() {
        val flag = f("a")
        cache.remember("id", "a", flag, started = now, trusted = true)
        now += CACHE_TTL_MS
        assertSame(flag, cache.recall("id"))
        now += 1
        assertNull(cache.recall("id"))
        assertEquals(0, cache.size)
        assertNull(cache.recall("never"))
    }

    @Test
    fun `the oldest write is dropped past the cap, and a rewrite renews age`() {
        cache.remember("1", "a", f("a"), now, true)
        cache.remember("2", "b", f("b"), now, true)
        cache.remember("3", "c", f("c"), now, true)
        cache.remember("1", "a", f("a"), now, true)
        cache.remember("4", "d", f("d"), now, true)
        assertEquals(3, cache.size)
        assertNull(cache.recall("2"))
        assertTrue(cache.recall("1") != null)
        assertTrue(cache.recall("3") != null)
    }

    @Test
    fun `drop removes the named keys, and an empty list drops everything`() {
        cache.remember("1", "a", f("a"), now, true)
        cache.remember("2", "b", f("b"), now, true)
        cache.remember("3", "a", f("a"), now, true)
        cache.drop(listOf("a"))
        assertNull(cache.recall("1"))
        assertNull(cache.recall("3"))
        assertTrue(cache.recall("2") != null)
        cache.drop(emptyList())
        assertEquals(0, cache.size)
    }

    @Test
    fun `forget and clear remove entries`() {
        cache.remember("1", "a", f("a"), now, true)
        cache.remember("2", "b", f("b"), now, true)
        cache.forget("1")
        assertNull(cache.recall("1"))
        cache.clear()
        assertEquals(0, cache.size)
    }

    @Test
    fun `a drop or a state change marks reads in flight stale, only for their key`() {
        val a = cache.begin("a")
        val b = cache.begin("b")
        cache.drop(listOf("a"))
        assertTrue(a.stale)
        assertFalse(b.stale)
        cache.drop(emptyList())
        assertTrue(b.stale)
        cache.end(a)
        cache.end(b)
        val c = cache.begin("c")
        cache.markPendingStale()
        assertTrue(c.stale)
        cache.end(c)
        val d = cache.begin("d")
        assertEquals(1, cache.pendingCount)
        cache.end(d)
        assertEquals(0, cache.pendingCount)
        cache.markPendingStale()
        assertFalse(d.stale)
    }
}
