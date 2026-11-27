package com.intempt.core.flags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `/flags/stream` reconnect loop (F15): backoff 1 s doubling to 60 s, reset by an open. */
class FlagStreamTest {
    private class Handle : StreamHandle {
        var closed = false

        override fun close() {
            closed = true
        }
    }

    private val opened = mutableListOf<Pair<StreamHandlers, Handle>>()
    private val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
    private val cancelled = mutableListOf<Long>()
    private val changes = mutableListOf<List<String>>()
    private val states = mutableListOf<Boolean>()

    private val stream =
        FlagStream(
            open = { h -> Handle().also { opened += h to it } },
            schedule = { delay, action ->
                scheduled += delay to action
                Cancellable { cancelled += delay }
            },
            onChange = { changes += it },
            onState = { states += it },
        )

    private fun last() = opened.last().first

    private fun fireTimer() = scheduled.removeAt(0).second()

    @Test
    fun `start opens once and reports connected between open and close`() {
        stream.start()
        stream.start()
        assertEquals(1, opened.size)
        assertFalse(stream.connected)
        last().onOpen()
        assertTrue(stream.connected)
        last().onOpen()
        assertEquals(listOf(true), states)
        last().onClose()
        assertFalse(stream.connected)
        assertEquals(listOf(true, false), states)
    }

    @Test
    fun `retries after 1 s doubling to a 60 s ceiling`() {
        stream.start()
        val delays = mutableListOf<Long>()
        repeat(8) {
            last().onClose()
            delays += scheduled.single().first
            stream.start()
            assertEquals(it + 1, opened.size)
            fireTimer()
        }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L), delays)
    }

    @Test
    fun `an open resets the backoff to 1 s`() {
        stream.start()
        last().onClose()
        fireTimer()
        last().onClose()
        assertEquals(2_000L, scheduled.single().first)
        fireTimer()
        last().onOpen()
        last().onClose()
        assertEquals(1_000L, scheduled.single().first)
    }

    @Test
    fun `only flags_changed events from the live connection reach onChange`() {
        stream.start()
        val first = last()
        first.onMessage("flags_changed", """{"keys":["a"]}""")
        first.onMessage("message", """{"keys":["b"]}""")
        first.onMessage("flags_changed", "garbage")
        assertEquals(listOf(listOf("a"), emptyList()), changes)
        first.onClose()
        fireTimer()
        first.onMessage("flags_changed", """{"keys":["z"]}""")
        first.onOpen()
        first.onClose()
        assertEquals(2, changes.size)
        assertFalse(stream.connected)
        assertTrue(scheduled.isEmpty())
    }

    @Test
    fun `stop closes the connection and cancels the timer, and start reconnects`() {
        stream.start()
        last().onOpen()
        stream.stop()
        assertTrue(opened.last().second.closed)
        assertFalse(stream.connected)
        assertEquals(listOf(true, false), states)
        last().onClose()
        assertTrue(scheduled.isEmpty())
        stream.start()
        assertEquals(2, opened.size)
        last().onClose()
        stream.stop()
        assertEquals(listOf(1_000L), cancelled)
        stream.start()
        assertEquals(3, opened.size)
    }

    @Test
    fun `close is final`() {
        stream.start()
        last().onOpen()
        stream.close()
        assertFalse(stream.connected)
        stream.start()
        assertEquals(1, opened.size)
        last().onClose()
        assertTrue(scheduled.isEmpty())
    }

    @Test
    fun `a timer that fires after stop does not reconnect`() {
        stream.start()
        last().onClose()
        val timer = scheduled.single().second
        stream.stop()
        timer()
        assertEquals(1, opened.size)
    }

    @Test
    fun `a connection stopped and replaced stays dead to its own callbacks`() {
        stream.start()
        val old = last()
        stream.stop()
        stream.start()
        old.onOpen()
        assertFalse(stream.connected)
        old.onClose()
        assertTrue(scheduled.isEmpty())
        last().onOpen()
        assertTrue(stream.connected)
    }
}
