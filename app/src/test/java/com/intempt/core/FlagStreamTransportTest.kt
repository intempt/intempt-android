@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core

import com.intempt.core.types.FlagContext
import com.intempt.core.types.FlagReason
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * F15 end to end on the real ktor client: the first read opens `/flags/stream` with the Basic key,
 * reads are served from memory while it is connected, and a `flags_changed` event drops the key so
 * the next read asks the server again. It does not hold the stream past the client-wide 30 s
 * request timeout, so the lifted timeout on the stream request is not covered here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FlagStreamTransportTest {
    private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val evaluations = CopyOnWriteArrayList<String>()
    private val streamHeads = CopyOnWriteArrayList<String>()
    private val streamOpen = CountDownLatch(1)

    @Volatile
    private var stream: OutputStream? = null

    @Volatile
    private var value = "true"

    @Volatile
    private var reason = "targeted"

    init {
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val conn =
                    try {
                        socket.accept()
                    } catch (_: IOException) {
                        break
                    }
                thread(isDaemon = true) { serve(conn) }
            }
        }
    }

    private fun serve(conn: Socket) {
        val input = conn.getInputStream()
        val head = readHead(input)
        val path = head.lineSequence().first().split(' ')[1]
        if (path.endsWith("/stream")) {
            streamHeads += head
            val out = conn.getOutputStream()
            val open = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n"
            out.write("$open:keepalive\n\n".toByteArray())
            out.flush()
            stream = out
            streamOpen.countDown()
            return
        }
        conn.use { c ->
            val length =
                head.lines().first { it.startsWith("content-length:", true) }.substringAfter(':').trim().toInt()
            repeat(length) { input.read() }
            evaluations += path
            val bytes = """{"key":"k","value":$value,"variationId":1,"reason":"$reason"}""".toByteArray()
            c.getOutputStream().apply {
                val ok = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n"
                write("${ok}Connection: close\r\n\r\n".toByteArray() + bytes)
                flush()
            }
        }
    }

    private fun readHead(input: InputStream): String {
        val out = ByteArrayOutputStream()
        while (!out.toString().endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) break
            out.write(b)
        }
        return out.toString()
    }

    @After
    fun tearDown() {
        socket.close()
    }

    private suspend fun waitFor(condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
    }

    @Test
    fun `the stream keeps reads in memory and a change event sends the next read to the server`() =
        runBlocking<Unit> {
            val flags = flagsServiceAt("http://127.0.0.1:${socket.localPort}/v1/o/projects/p/flags")

            flags.detail("k", FlagContext(), false)
            assertTrue(streamOpen.await(5, TimeUnit.SECONDS))
            val head = streamHeads.single()
            assertTrue(head, head.startsWith("GET /v1/o/projects/p/flags/stream "))
            assertTrue(head, head.contains("Authorization: Basic c2VjcmV0LWtleQ=="))
            assertTrue(head, head.contains("Accept: text/event-stream"))
            Thread.sleep(200)

            flags.detail("k", FlagContext(), false)
            assertEquals(2, evaluations.size)
            assertEquals(true, flags.detail("k", FlagContext(), false).value)
            assertEquals(true, flags.detail("k", FlagContext(), false).value)
            assertEquals(2, evaluations.size)

            value = "null"
            reason = "killed"
            stream!!.apply {
                write("event: flags_changed\ndata: {\"reason\":\"kill\",\"keys\":[\"k\"]}\n\n".toByteArray())
                flush()
            }
            waitFor { flags.detail("k", FlagContext(), false).reason == FlagReason.KILLED }
            val killed = flags.detail("k", FlagContext(), false)
            assertEquals(false, killed.value)
            assertEquals(FlagReason.KILLED, killed.reason)
            assertTrue("a served null is not cached", evaluations.size >= 4)
        }
}
