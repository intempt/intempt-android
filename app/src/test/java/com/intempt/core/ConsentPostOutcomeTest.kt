package com.intempt.core

import com.intempt.core.internal.InternalIntemptApi
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.HttpManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.isNeverReached
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import javax.net.ssl.SSLHandshakeException
import kotlin.concurrent.thread

/**
 * R3/R5 (Beso 2026-11-13): a consent that never reached the server is kept, one that may have
 * reached it is dropped. postForStatus has to tell the two apart, which it did not: every
 * exception came back as status 0.
 */
@OptIn(InternalIntemptApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConsentPostOutcomeTest {
    private fun service() = HttpManagerService(mock<ConfigManagerService>(), mock<LoggerManagerService>())

    @Test
    fun `connection-phase failures are never-reached, anything later is maybe-reached`() {
        for (t in listOf(
            UnknownHostException("dns"),
            ConnectException("refused"),
            NoRouteToHostException("route"),
            ConnectTimeoutException("connect timed out"),
            SSLHandshakeException("tls"),
            UnresolvedAddressException(),
            IOException("wrapped", ConnectException("refused")),
        )) {
            assertTrue(t.toString(), isNeverReached(t))
        }
        for (t in listOf(
            SocketTimeoutException("read timed out"),
            HttpRequestTimeoutException("u", 1L),
            IOException("unexpected end of stream"),
            IllegalStateException("x"),
        )) {
            assertFalse(t.toString(), isNeverReached(t))
        }
    }

    @Test
    fun `a refused connection is reported as not sent`() {
        val port = ServerSocket(0).use { it.localPort }

        val outcome = runBlocking { service().postForStatus("http://127.0.0.1:$port/consents", JSONObject()) }

        assertEquals(0, outcome.status)
        assertTrue("a refused connect never reached the server", outcome.notSent)
    }

    @Test
    fun `a connection closed after the request was written is not reported as not sent`() {
        val server = ServerSocket(0)
        val worker =
            thread {
                server.accept().use { socket ->
                    val input = socket.getInputStream()
                    val buf = ByteArray(8192)
                    // Read the request through its body, then hang up without answering.
                    var seen = ""
                    while (!seen.contains("{}")) {
                        val n = input.read(buf)
                        if (n < 0) break
                        seen += String(buf, 0, n)
                    }
                }
            }

        val outcome =
            server.use {
                runBlocking { service().postForStatus("http://127.0.0.1:${server.localPort}/consents", JSONObject()) }
            }
        worker.join(5_000)

        assertEquals(0, outcome.status)
        assertFalse("the server may hold a consent it read, so it must not be resent", outcome.notSent)
    }
}
