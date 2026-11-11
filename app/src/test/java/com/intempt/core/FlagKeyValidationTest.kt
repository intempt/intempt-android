@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core

import com.intempt.core.customCapture.CustomCaptureComponent
import com.intempt.core.customCapture.CustomCaptureService
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.ErrorReporter
import com.intempt.core.services.IntemptEventManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.StorageManagerService
import com.intempt.core.services.UtilsService
import com.intempt.core.services.eventPool.EventPoolManagerService
import com.intempt.core.types.ChooseResult
import com.intempt.core.types.FlagContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull

/**
 * INT-7455: the choose service only accepts flag names matching `^[a-zA-Z0-9_-]+$`. A key outside
 * it was sent, answered 400, and came back as the default — the same value a paused flag returns.
 * `docs/CONVENTIONS.md` makes a caller mistake a throw, so it must fail before any request.
 */
class FlagKeyValidationTest {
    private val eventPool = mock(EventPoolManagerService::class.java)
    private val component =
        CustomCaptureComponent(
            mock(CustomCaptureService::class.java),
            mock(ConfigManagerService::class.java),
            eventPool,
            mock(IntemptEventManagerService::class.java),
            UtilsService(mock(LoggerManagerService::class.java)),
            mock(StorageManagerService::class.java),
            mock(ErrorReporter::class.java),
        )

    @Test
    fun `a key outside the server's pattern throws and sends nothing`(): Unit =
        runBlocking {
            for (key in listOf("a.b", "a b", "a/b", "ключ")) {
                assertThrows(key, IllegalArgumentException::class.java) {
                    runBlocking { component.variationDetailInternal(key, FlagContext(), "fallback") }
                }
            }
            verify(eventPool, never()).chooseFlags(any(), anyOrNull())
        }

    @Test
    fun `a key inside the pattern is looked up`(): Unit =
        runBlocking {
            `when`(eventPool.chooseFlags(any(), anyOrNull())).thenReturn(ChooseResult(emptyList(), null))

            assertEquals("d", component.variationDetailInternal("a_b-1", FlagContext(), "d").value)
            assertEquals("d", component.variationDetailInternal("ABC", FlagContext(), "d").value)

            verify(eventPool).chooseFlags(FlagContext(), listOf("a_b-1"))
            verify(eventPool).chooseFlags(FlagContext(), listOf("ABC"))
        }
}
