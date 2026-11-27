package com.intempt.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A choose or flags endpoint on loopback that answers each request with the next canned (status,
 * body) and keeps every request body and path. MockWebServer is not on this module's classpath, and
 * the flag path goes through ktor's real Android engine, so the request has to cross a real socket.
 */
internal class StubChooseServer(private vararg val answers: Pair<Int, String>) : AutoCloseable {
    private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val bodies = CopyOnWriteArrayList<String>()
    val paths = CopyOnWriteArrayList<String>()
    val url: String get() = "http://127.0.0.1:${socket.localPort}/choose"
    val flagsBase: String get() = "http://127.0.0.1:${socket.localPort}/v1/o/projects/p/flags"

    init {
        thread(isDaemon = true) {
            var served = 0
            while (!socket.isClosed) {
                val conn =
                    try {
                        socket.accept()
                    } catch (_: IOException) {
                        break
                    }
                conn.use { c ->
                    val input = c.getInputStream()
                    val requestHead = readHead(input)
                    paths += requestHead.lineSequence().first().split(' ').getOrElse(1) { "" }
                    val length =
                        requestHead.lines()
                            .firstOrNull { it.startsWith("content-length:", ignoreCase = true) }
                            ?.substringAfter(':')?.trim()?.toInt() ?: 0
                    val body = String(ByteArray(length).also { readFully(input, it) })
                    // The flag stream opens on the first read; it must not take a canned answer
                    // meant for the read itself, so it is refused and retries on its own backoff.
                    val (status, text) =
                        if (paths.last().endsWith("/stream")) {
                            404 to "{}"
                        } else {
                            bodies += body
                            answers[minOf(served++, answers.lastIndex)]
                        }
                    val bytes = text.toByteArray()
                    val head =
                        "HTTP/1.1 $status S\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    c.getOutputStream().apply {
                        write(head.toByteArray() + bytes)
                        flush()
                    }
                }
            }
        }
    }

    override fun close() = socket.close()

    private fun readHead(input: InputStream): String {
        val out = ByteArrayOutputStream()
        while (!out.toString().endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) break
            out.write(b)
        }
        return out.toString()
    }

    private fun readFully(
        input: InputStream,
        into: ByteArray,
    ) {
        var off = 0
        while (off < into.size) {
            val n = input.read(into, off, into.size - off)
            if (n < 0) break
            off += n
        }
    }
}
