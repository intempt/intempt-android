@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core.services

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * INT-3914 — `post()` logged the full request body (userId, email, user attributes, captured UI
 * text) both before sending and again after a 2xx. Neither log line may contain the body.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class HttpManagerServicePostLogRedactionTest {
    private val secret = "pii-marker-jane.doe@example.com"

    private fun postAndCollectLogs(status: Int): List<String> {
        val server = ServerSocket(0)
        // Minimal HTTP/1.1 responder: drain the request head, answer with an empty body.
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { sock ->
                    val input = sock.getInputStream()
                    val buf = ByteArray(8192)
                    var seen = ""
                    while (!seen.contains("\r\n\r\n")) {
                        val n = input.read(buf)
                        if (n < 0) break
                        seen += String(buf, 0, n)
                    }
                    Thread.sleep(150) // let the small body arrive
                    sock.getOutputStream().write(
                        "HTTP/1.1 $status X\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(),
                    )
                }
            }
        }
        try {
            val config = ConfigManagerService(RuntimeEnvironment.getApplication())
            val logger = spy(LoggerManagerService(config))
            val http = HttpManagerService(config, logger)
            runBlocking { http.post("http://127.0.0.1:${server.localPort}/e", JSONObject().put("email", secret)) }
            val logged = argumentCaptor<String>()
            verify(logger, atLeastOnce()).log(logged.capture())
            return logged.allValues
        } finally {
            server.close()
        }
    }

    @Test
    fun `successful post does not log the request body`() {
        val lines = postAndCollectLogs(200)
        assertTrue("expected the post to be logged at all", lines.isNotEmpty())
        assertFalse("body leaked into logs: $lines", lines.any { it.contains(secret) })
    }

    @Test
    fun `post logs still name the url`() {
        val lines = postAndCollectLogs(200)
        assertNotNull(lines.firstOrNull { it.contains("/e") })
    }
}
