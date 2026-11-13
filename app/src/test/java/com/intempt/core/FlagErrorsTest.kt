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
import com.intempt.core.types.FlagError
import com.intempt.core.types.FlagErrorKind
import com.intempt.core.types.FlagReason
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.ServerSocket

/**
 * INT-7454 / CONTRACT-R2 §S3: a flag read that fell back says why. Before this every failure —
 * a rejected key, a 5xx, a refused connection, a garbage body — returned the default with nothing
 * to tell them apart, so a revoked API key looked exactly like a paused flag.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FlagErrorsTest {
    private var server: StubChooseServer? = null
    private val logger = mock(LoggerManagerService::class.java)
    private val seen = mutableListOf<FlagError>()

    @After
    fun tearDown() {
        server?.close()
    }

    private fun component(url: String): CustomCaptureComponent {
        val config = mock(ConfigManagerService::class.java)
        `when`(config.token()).thenReturn("c2VjcmV0LWtleQ==")
        `when`(config.certificatePins).thenReturn(emptyList())
        `when`(config.optimizationUrl).thenReturn(url)
        `when`(config.sourceId).thenReturn("src-1")
        val events = mock(IntemptEventManagerService::class.java)
        `when`(events.generateChooseBody(any(), anyOrNull())).thenReturn(mapOf("device" to "mobile"))
        val pool =
            EventPoolManagerService(
                config,
                logger,
                HttpManagerService(config, logger),
                events,
                mock(DeliveryMessages::class.java),
            )
        return CustomCaptureComponent(
            mock(CustomCaptureService::class.java),
            config,
            pool,
            events,
            UtilsService(logger),
            mock(StorageManagerService::class.java),
            mock(ErrorReporter::class.java),
        ).also { c -> c.onFlagError = { seen += it } }
    }

    private fun serving(vararg answers: Pair<Int, String>): CustomCaptureComponent =
        component(StubChooseServer(*answers).also { server = it }.url)

    @Test
    fun `a rejected key returns the default with reason ERROR and an auth error, and fires the hook`() =
        runBlocking<Unit> {
            val detail = serving(401 to "{}").variationDetailInternal("k", FlagContext(), "dflt")

            assertEquals("dflt", detail.value)
            assertEquals(FlagReason.ERROR, detail.reason)
            assertEquals(FlagErrorKind.AUTH, detail.error?.kind)
            assertEquals(401, detail.error?.status)
            assertEquals("k", detail.error?.key)
            assertEquals(1, seen.size)
            assertEquals(FlagErrorKind.AUTH, seen.single().kind)
        }

    @Test
    fun `401 then 403 logs one error naming the source and never the key, then debug`() =
        runBlocking<Unit> {
            val c = serving(401 to "{}", 403 to "{}")
            c.variationDetailInternal("k", FlagContext(), "d")
            c.variationDetailInternal("k", FlagContext(), "d")

            assertEquals(listOf(401, 403), seen.map { it.status })
            val errors = argumentCaptor<String>()
            verify(logger, atLeastOnce()).error(errors.capture())
            val authLines = errors.allValues.filter { it.contains("src-1") }
            assertEquals(1, authLines.size)
            assertFalse(authLines.single().contains("c2VjcmV0LWtleQ"))
            assertFalse(authLines.single().contains("secret-key"))
            val debug = argumentCaptor<String>()
            verify(logger, atLeastOnce()).debug(debug.capture())
            assertTrue(debug.allValues.any { it.contains("auth") })
        }

    @Test
    fun `statuses map to their kinds through the real transport`() =
        runBlocking<Unit> {
            val c = serving(500 to "{}", 408 to "{}", 429 to "{}", 404 to "{}")
            repeat(4) { c.variationDetailInternal("k", FlagContext(), "d") }

            assertEquals(
                listOf(FlagErrorKind.SERVER, FlagErrorKind.TIMEOUT, FlagErrorKind.SERVER, FlagErrorKind.BAD_REQUEST),
                seen.map { it.kind },
            )
            assertEquals(listOf(500, 408, 429, 404), seen.map { it.status })
        }

    @Test
    fun `a refused connection is a network error with no status`() =
        runBlocking<Unit> {
            val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
            val detail = component("http://127.0.0.1:$port/choose").variationDetailInternal("k", FlagContext(), "d")

            assertEquals(FlagReason.ERROR, detail.reason)
            assertEquals(FlagErrorKind.NETWORK, detail.error?.kind)
            assertNull(detail.error?.status)
        }

    @Test
    fun `a 200 that is not a choices array is a parse error`() =
        runBlocking<Unit> {
            val c = serving(200 to "not json", 200 to """{"choices":"x"}""", 200 to "{}", 200 to "[]")
            repeat(4) { assertEquals("d", c.variationDetailInternal("k", FlagContext(), "d").value) }

            assertEquals(List(4) { FlagErrorKind.PARSE }, seen.map { it.kind })
        }

    @Test
    fun `an answered read with no choice for the key is OFF, not an error`() =
        runBlocking<Unit> {
            val detail = serving(200 to """{"choices":[]}""").variationDetailInternal("k", FlagContext(), "d")

            assertEquals("d", detail.value)
            assertEquals(FlagReason.OFF, detail.reason)
            assertNull(detail.error)
            assertTrue(seen.isEmpty())
        }

    @Test
    fun `a served value carries no error`() =
        runBlocking<Unit> {
            val c = serving(200 to """{"choices":[{"name":"k","body":true}]}""")
            val detail = c.variationDetailInternal("k", FlagContext(), false)

            assertEquals(true, detail.value)
            assertNull(detail.error)
            assertTrue(seen.isEmpty())
        }

    @Test
    fun `an allFlags failure is reported with no key`() =
        runBlocking<Unit> {
            assertEquals(emptyMap<String, Any?>(), serving(503 to "{}").allFlags(FlagContext()))

            assertNull(seen.single().key)
            assertEquals(FlagErrorKind.SERVER, seen.single().kind)
        }

    @Test
    fun `a throwing hook is swallowed and logged and the default still comes back`() =
        runBlocking<Unit> {
            val c = serving(500 to "{}")
            c.onFlagError = { error("host bug") }

            assertEquals("d", c.variationDetailInternal("k", FlagContext(), "d").value)
            val errors = argumentCaptor<String>()
            verify(logger, atLeastOnce()).error(errors.capture())
            assertTrue(errors.allValues.any { it.contains("onFlagError") && it.contains("host bug") })
        }

    @Test
    fun `no hook set is not an error`() =
        runBlocking<Unit> {
            val c = serving(500 to "{}")
            c.onFlagError = null

            assertEquals("d", c.variationDetailInternal("k", FlagContext(), "d").value)
            verify(logger, never()).error(org.mockito.kotlin.argThat<String> { contains("onFlagError") })
            verify(logger, times(0)).error(org.mockito.kotlin.argThat<String> { contains("src-1") })
        }
}
