@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core.services.eventPool

import android.content.Context
import com.intempt.core.eventModels.IntemptEvent
import com.intempt.core.queue.DeliveryMessages
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.HttpManagerService
import com.intempt.core.services.IntemptEventManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.types.DispatchEventProps
import com.intempt.core.types.IntemptEventProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Guards the consent gate on `dispatchEvent`, the single choke point every autocapture source
 * (session, lifecycle, touch, screen) routes through before an event can reach the durable queue.
 * A regression here either leaks events after opt-out (a real privacy defect, not just a bug) or
 * silently drops every event after opt-in is granted late in a session.
 *
 * Also guards `emitEvent`'s pass-through of the underlying `SharedFlow.tryEmit` result, which the
 * eventPool's own comment says is expected to return false under a full buffer — a caller that
 * cannot see that return value would never know an event was dropped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventPoolManagerServiceTest {
    private lateinit var config: ConfigManagerService
    private lateinit var logger: LoggerManagerService
    private lateinit var http: HttpManagerService
    private lateinit var intemptEvent: IntemptEventManagerService
    private lateinit var delivery: DeliveryMessages
    private lateinit var service: EventPoolManagerService

    @Before
    fun setUp() {
        config = mock()
        logger = mock()
        http = mock()
        intemptEvent = mock()
        delivery = mock()

        service =
            EventPoolManagerService(
                config = config,
                logger = logger,
                http = http,
                intemptEvent = intemptEvent,
                delivery = delivery,
                dispatcher = UnconfinedTestDispatcher(),
            )
    }

    @Test
    fun `an opted-out user's events never reach the event stream`() {
        whenever(config.isUserOptIn).thenReturn(false)

        val payload: Array<IntemptEventProvider> = emptyArray()
        val props =
            DispatchEventProps(
                eventName = "Purchase",
                entityName = "track",
                event = payload,
                type = "track",
                context = mock<Context>(),
            )

        service.dispatchEvent(props, "TestSource")

        // dispatchEvent has no return value of its own, so assert via the observable side
        // effect: nothing is ever emitted onto the shared flow that subscribers read from.
        assertFalse(service.eventReceiver.replayCache.isNotEmpty())
    }

    @Test
    fun `an opted-in user with an inline event payload emits immediately without going through the handler dispatch`() {
        whenever(config.isUserOptIn).thenReturn(true)

        val payload: Array<IntemptEventProvider> = emptyArray()
        val props =
            DispatchEventProps(
                eventName = "Purchase",
                entityName = "track",
                event = payload,
                type = "track",
                context = mock<Context>(),
            )

        service.dispatchEvent(props, "TestSource")

        assertTrue(service.eventReceiver.replayCache.isNotEmpty())
        assertEquals("Purchase", service.eventReceiver.replayCache.last().getEventName())
    }

    @Test
    fun `emitEvent reports whether the shared flow actually accepted the event`() {
        val event: IntemptEvent = mock()
        whenever(event.getEventType()).thenReturn("track")

        val accepted = service.emitEvent(event)

        // A freshly constructed pool's buffer is never full, so the first emit must succeed and
        // must report success rather than swallowing tryEmit's return value.
        assertTrue(accepted)
    }
}
