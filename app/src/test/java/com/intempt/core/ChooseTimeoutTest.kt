@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core

import com.intempt.core.customCapture.CustomCaptureComponent
import com.intempt.core.customCapture.CustomCaptureService
import com.intempt.core.queue.DeliveryMessages
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.ErrorReporter
import com.intempt.core.services.HttpManagerService
import com.intempt.core.services.IntemptEventManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.StorageManagerService
import com.intempt.core.services.UtilsService
import com.intempt.core.services.eventPool.EventPoolManagerService
import com.intempt.core.types.FlagContext
import com.intempt.core.types.FlagErrorKind
import com.intempt.core.types.FlagReason
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.ServerSocket

/**
 * INT-7445 / INT-7457: a flag lookup is on the caller's critical path (a screen waits for it), so
 * it gets 2 s, not the 30 s the shared client allows for consent and feeds.
 *
 * Wall clock and `runBlocking`, not `runTest`: virtual time would skip ktor's timeout and pass a
 * 30 s client instantly. The server never calls `accept()`, so the OS backlog completes the
 * handshake and the request is sent into a connection that never answers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChooseTimeoutTest {
    private val stalled = ServerSocket(0, 50, InetAddress.getLoopbackAddress())

    @After
    fun tearDown() {
        stalled.close()
    }

    @Test
    fun `a stalled choose server yields the default within about two seconds`() =
        runBlocking {
            val config = mock(ConfigManagerService::class.java)
            `when`(config.token()).thenReturn("dG9rZW4=")
            `when`(config.sourceId).thenReturn("s")
            `when`(config.certificatePins).thenReturn(emptyList())
            `when`(config.optimizationUrl).thenReturn("http://127.0.0.1:${stalled.localPort}/choose")
            val events = mock(IntemptEventManagerService::class.java)
            `when`(events.generateChooseBody(any(), anyOrNull())).thenReturn(mapOf("sourceId" to "s"))
            val logger = mock(LoggerManagerService::class.java)
            val pool =
                EventPoolManagerService(
                    config,
                    logger,
                    HttpManagerService(config, logger),
                    events,
                    mock(DeliveryMessages::class.java),
                )
            val component =
                CustomCaptureComponent(
                    mock(CustomCaptureService::class.java),
                    config,
                    pool,
                    events,
                    UtilsService(logger),
                    mock(StorageManagerService::class.java),
                    mock(ErrorReporter::class.java),
                )

            val start = System.nanoTime()
            val detail = component.variationDetailInternal("checkout", FlagContext(), "fallback")
            val elapsedMs = (System.nanoTime() - start) / 1_000_000

            assertEquals("fallback", detail.value)
            // INT-7454: the fallback says why, and a stall is a timeout, not a network failure.
            assertEquals(FlagReason.ERROR, detail.reason)
            assertEquals(FlagErrorKind.TIMEOUT, detail.error?.kind)
            assertTrue("took $elapsedMs ms", elapsedMs in 1_500L until 5_000L)
        }

    @Test
    fun `the choose timeout is pinned at 2000 ms and the client-wide limit is untouched`() {
        assertEquals(2_000L, HttpManagerService.CHOOSE_TIMEOUT_MS)
        assertEquals(30_000L, HttpManagerService.REQUEST_TIMEOUT_MS)
    }
}
