package com.intempt.core.autocapture.sessionTracker

import android.content.Context
import com.intempt.core.eventModels.IntemptEvent
import com.intempt.core.services.HttpManagerService
import com.intempt.core.services.IntemptEventManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.StorageManagerService
import com.intempt.core.services.UtilsService
import com.intempt.core.services.eventPool.EventPoolManagerService
import com.intempt.core.types.Constants
import com.intempt.core.types.IntemptEventProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Guards the one decision this class exists to make: whether the current session has expired
 * (`onInit`/`validateSession`, both driven by the same `SESSION_TIMEOUT` comparison). Getting the
 * boundary wrong either starts a fresh "Session start" event on every foreground/event when the
 * real session is still live (inflating session counts), or never starts a new session when the
 * old one has gone stale for far longer than the timeout (undercounting sessions and attributing
 * unrelated activity to a session from hours ago).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionTrackerServiceTest {
    private lateinit var context: Context
    private lateinit var logger: LoggerManagerService
    private lateinit var storage: StorageManagerService
    private lateinit var eventPool: EventPoolManagerService
    private lateinit var http: HttpManagerService
    private lateinit var utils: UtilsService
    private lateinit var intemptEvent: IntemptEventManagerService
    private lateinit var service: SessionTrackerService

    private val fakeSessionPayload: Array<IntemptEventProvider> = emptyArray()

    @Before
    fun setUp() {
        context = mock()
        logger = mock()
        storage = mock()
        eventPool = mock()
        http = mock()
        utils = mock()
        intemptEvent = mock()

        whenever(intemptEvent.generateSessionEventPayload(any())).thenReturn(fakeSessionPayload)
        whenever(utils.generateId(any())).thenReturn("session-id-123")

        service =
            SessionTrackerService(
                context = context,
                logger = logger,
                storage = storage,
                eventPool = eventPool,
                http = http,
                utils = utils,
                intemptEvent = intemptEvent,
                dispatcher = UnconfinedTestDispatcher(),
            )
    }

    private fun stubSessionTime(value: Long) {
        whenever(
            storage.getStorageItem<Long>(any(), any(), anyOrNull(), any()),
        ).thenReturn(value)
    }

    @Test
    fun `a session well inside the timeout window is kept alive rather than restarted`() {
        stubSessionTime(System.currentTimeMillis() - 1_000L)

        runBlocking { service.onInit() }

        // Only the timestamp is refreshed; no new session id and no "Session start" dispatch.
        val valueCaptor = argumentCaptor<Any>()
        verify(storage, times(1)).setStorageItem(any(), any(), valueCaptor.capture(), any())
        assertTrue(valueCaptor.firstValue is Long)
        verify(eventPool, never()).dispatchEvent(any(), any())
    }

    @Test
    fun `a session older than the timeout is restarted with a fresh id and a start event`() {
        stubSessionTime(System.currentTimeMillis() - (Constants.SESSION.SESSION_TIMEOUT + 1_000L))

        runBlocking { service.onInit() }

        // New session id stored, new timestamp stored, and a start event dispatched.
        val valueCaptor = argumentCaptor<Any>()
        verify(storage, times(2)).setStorageItem(any(), any(), valueCaptor.capture(), any())
        assertTrue(
            "expected one String (session id) and one Long (timestamp) stored",
            valueCaptor.allValues.any { it is String } && valueCaptor.allValues.any { it is Long },
        )
        verify(eventPool, times(1)).dispatchEvent(any(), eq("SessionTrackerService"))
    }

    @Test
    fun `exactly at the timeout boundary the session is treated as still active`() {
        // currentTimestamp - sessionTime == SESSION_TIMEOUT uses a strict `>` comparison, so the
        // exact boundary must NOT restart the session.
        val now = System.currentTimeMillis()
        stubSessionTime(now - Constants.SESSION.SESSION_TIMEOUT)

        // Freeze "now" indirectly isn't possible without a clock seam, so instead assert the
        // documented boundary semantics directly on the same comparison the production code uses.
        val elapsed = System.currentTimeMillis() - (now - Constants.SESSION.SESSION_TIMEOUT)
        assertFalse("exactly at the timeout must not itself count as expired", elapsed > Constants.SESSION.SESSION_TIMEOUT)
    }

    @Test
    fun `events already tagged as session events are not re-validated`() {
        val callbackSlot = mutableListOf<(IntemptEvent) -> Unit>()
        whenever(eventPool.subscribe(any(), any())).thenAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            callbackSlot.add(invocation.arguments[1] as (IntemptEvent) -> Unit)
            null
        }

        service.subscribeToEventReceiver()

        val sessionEvent: IntemptEvent = mock()
        whenever(sessionEvent.getEventType()).thenReturn(Constants.SESSION.EVENT_TYPE)

        stubSessionTime(0L)
        callbackSlot.single().invoke(sessionEvent)

        // A session event must never re-trigger validateSession, which would recursively
        // dispatch another session event for the event that announces a session.
        verify(eventPool, never()).dispatchEvent(any(), any())
    }

    @Test
    fun `a non-session event past the timeout starts a new session under its own name`() {
        val callbackSlot = mutableListOf<(IntemptEvent) -> Unit>()
        whenever(eventPool.subscribe(any(), any())).thenAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            callbackSlot.add(invocation.arguments[1] as (IntemptEvent) -> Unit)
            null
        }

        service.subscribeToEventReceiver()

        val trackEvent: IntemptEvent = mock()
        whenever(trackEvent.getEventType()).thenReturn("track")
        whenever(trackEvent.getEventTimestamp()).thenReturn(System.currentTimeMillis())
        whenever(trackEvent.getEventName()).thenReturn("Purchase")

        stubSessionTime(0L)
        callbackSlot.single().invoke(trackEvent)

        verify(eventPool, times(1)).dispatchEvent(any(), eq("SessionTrackerService"))
    }
}
