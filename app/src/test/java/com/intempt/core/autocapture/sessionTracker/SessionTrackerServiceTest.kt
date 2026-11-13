@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core.autocapture.sessionTracker

import android.content.Context
import com.intempt.core.services.IntemptEventManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.StorageManagerService
import com.intempt.core.services.eventPool.EventPoolManagerService
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * The session decision moved to `StorageManagerService.ensureSession` (INT-7446); its timeout
 * boundary is pinned to the millisecond in `SessionTrackerUnitTest`. What is left here is the one
 * thing this class still owns: the Session start event, installed only when sessions are on.
 */
class SessionTrackerServiceTest {
    private lateinit var storage: StorageManagerService
    private lateinit var eventPool: EventPoolManagerService
    private lateinit var intemptEvent: IntemptEventManagerService
    private lateinit var service: SessionTrackerService

    @Before
    fun setUp() {
        storage = mock()
        eventPool = mock()
        intemptEvent = mock()
        whenever(intemptEvent.generateSessionEventPayload(any())).thenReturn(emptyArray())

        service =
            SessionTrackerService(
                context = mock<Context>(),
                logger = mock<LoggerManagerService>(),
                storage = storage,
                eventPool = eventPool,
                intemptEvent = intemptEvent,
            )
    }

    @Test
    fun `enabling session events installs a hook that dispatches one Session start per call`() {
        verify(eventPool, never()).dispatchEvent(any(), any())

        service.enableSessionEvents()
        val hook = argumentCaptor<() -> Unit>()
        verify(storage).onSessionStart = hook.capture()
        hook.firstValue.invoke()

        verify(intemptEvent, times(1)).generateSessionEventPayload(eq("Session start"))
        verify(eventPool, times(1)).dispatchEvent(any(), eq("SessionTrackerService"))
    }
}
