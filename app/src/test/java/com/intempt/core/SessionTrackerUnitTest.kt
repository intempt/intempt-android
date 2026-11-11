@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core

import android.content.Context
import com.intempt.core.autocapture.sessionTracker.SessionTrackerComponent
import com.intempt.core.autocapture.sessionTracker.SessionTrackerService
import com.intempt.core.eventModels.IntemptEvent
import com.intempt.core.queue.DeliveryMessages
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.HttpManagerService
import com.intempt.core.services.IntemptEventManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.StorageManagerService
import com.intempt.core.services.UtilsService
import com.intempt.core.services.eventPool.EventPoolManagerService
import com.intempt.core.types.Constants
import com.intempt.core.types.DispatchEventProps
import com.intempt.core.types.StorageKeys
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionTrackerUnitTest {
    private lateinit var context: Context
    private lateinit var storage: StorageManagerService
    private lateinit var sessionTrackerService: SessionTrackerService
    private lateinit var sessionTrackerComponent: SessionTrackerComponent
    private lateinit var eventPool: EventPoolManagerService
    private lateinit var config: ConfigManagerService
    private lateinit var eventFlow: MutableSharedFlow<IntemptEvent>
    private lateinit var logger: LoggerManagerService
    private lateinit var httpSrv: HttpManagerService
    private lateinit var intemptEvent: IntemptEventManagerService
    private lateinit var utils: UtilsService

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setUp() {
        ShadowLog.stream = System.out
        ShadowLog.clear()

        context = spy(RuntimeEnvironment.getApplication())
        config = spy(ConfigManagerService(context))
        config.isLoggingEnabled = true
        logger = spy(LoggerManagerService(config))
        utils = spy(UtilsService(logger))

        storage = spy(StorageManagerService(context, utils))
        httpSrv = spy(HttpManagerService(config, logger))
        intemptEvent = spy(IntemptEventManagerService(context, storage, utils, config))
        // The dispatcher must be injected. EventPoolManagerService's init block launches a
        // flow collector on it, so leaving the default means a real Dispatchers.IO
        // coroutine outlives the test; anything it throws reaches the global uncaught
        // handler and is reported against whichever test runs next, in any class, since
        // Gradle shares one JVM across the suite. This was the only one of five
        // construction sites missing it.
        //
        // Unconfined rather than Standard, matching the other four sites: Standard queues
        // work until its scheduler is advanced, and nothing advances this one, so the
        // collector would never run and "should collect events" would fail.
        eventPool =
            spy(
                EventPoolManagerService(
                    config,
                    logger,
                    httpSrv,
                    intemptEvent,
                    mock(DeliveryMessages::class.java),
                    dispatcher = UnconfinedTestDispatcher(TestCoroutineScheduler()),
                ),
            )

        eventFlow = MutableSharedFlow<IntemptEvent>()

        `when`(eventPool.eventReceiver).thenReturn(eventFlow)

        sessionTrackerService =
            spy(
                SessionTrackerService(
                    context,
                    logger,
                    storage,
                    eventPool,
                    intemptEvent,
                ),
            )
        sessionTrackerComponent =
            spy(
                SessionTrackerComponent(
                    sessionTrackerService,
                ),
            )
    }

    /**
     * Replaces the onInit / subscriber tests: there is one minting path now (INT-7446), so a
     * rotation dispatches exactly one Session start, carrying the id the opening event carries.
     * The no-third-party-call property of the old runSessionStart test is kept.
     */
    @Test
    fun `with session events on a rotation dispatches one Session start ahead of the opening event`() {
        sessionTrackerComponent.start()

        val first = intemptEvent.generateIdentifyEventPayload("user-7446")[0]
        val second = intemptEvent.generateIdentifyEventPayload("user-7446")[0]

        val captor = argumentCaptor<DispatchEventProps>()
        verify(eventPool, times(1)).dispatchEvent(captor.capture(), anyString())
        assertEquals(Constants.SESSION.EVENT_TYPE, captor.firstValue.type)
        assertEquals(first.sessionId, captor.firstValue.event!![0].sessionId)
        assertEquals(first.sessionId, second.sessionId)
        verifyNoInteractions(httpSrv)
    }

    @Test
    fun `with session events off the id is minted and no Session start is dispatched`() {
        val event = intemptEvent.generateIdentifyEventPayload("user-7446")[0]

        assertTrue(event.sessionId.isNotEmpty())
        verify(eventPool, never()).dispatchEvent(any(), anyString())
    }

    // INT-7446, CONTRACT-R2 S5: the session id is identity. It exists before any event is built,
    // whatever the sessions switch says, and rotates on the first event after 30 min idle.

    @Test
    fun `an event built on a fresh install carries a session id with session events off`() {
        val event = intemptEvent.generateIdentifyEventPayload("user-7446")[0]

        assertTrue("sessionId must not be empty, was '${event.sessionId}'", event.sessionId.isNotEmpty())
    }

    @Test
    fun `the first event after 30 minutes idle carries a new session id`() {
        storage.setStorageItem(StorageKeys.SessionPrefs.key, StorageKeys.SessionId.key, "ses_old") { k, v ->
            putString(k, v)
        }
        val idleSince = System.currentTimeMillis() - (Constants.SESSION.SESSION_TIMEOUT + 1000)
        storage.setStorageItem(StorageKeys.SessionPrefs.key, StorageKeys.SessionTimestamp.key, idleSince) { k, v ->
            putLong(k, v)
        }

        val event = intemptEvent.generateIdentifyEventPayload("user-7446")[0]

        assertTrue(event.sessionId.isNotEmpty())
        assertNotEquals("ses_old", event.sessionId)
        assertEquals(event.sessionId, storage.getSessionId())
    }

    @Test
    fun `an event inside the idle window keeps the session id`() {
        storage.setStorageItem(StorageKeys.SessionPrefs.key, StorageKeys.SessionId.key, "ses_live") { k, v ->
            putString(k, v)
        }
        val recent = System.currentTimeMillis() - 1000
        storage.setStorageItem(StorageKeys.SessionPrefs.key, StorageKeys.SessionTimestamp.key, recent) { k, v ->
            putLong(k, v)
        }

        assertEquals("ses_live", intemptEvent.generateIdentifyEventPayload("user-7446")[0].sessionId)
    }

    @Test
    fun `the idle timeout is exclusive to the millisecond`() {
        val start = 1_000_000L
        storage.ensureSession(start)
        val id = storage.getSessionId()

        assertEquals(false, storage.ensureSession(start + Constants.SESSION.SESSION_TIMEOUT))
        assertEquals(id, storage.getSessionId())

        val activity = start + Constants.SESSION.SESSION_TIMEOUT
        assertEquals(true, storage.ensureSession(activity + Constants.SESSION.SESSION_TIMEOUT + 1))
        assertNotEquals(id, storage.getSessionId())
    }
}
