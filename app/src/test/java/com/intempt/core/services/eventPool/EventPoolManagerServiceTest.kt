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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executors

/**
 * Guards the consent gate on `dispatchEvent`, the single choke point every autocapture source
 * (session, lifecycle, touch, screen) routes through before an event can reach the durable queue.
 * A regression here either leaks events after opt-out (a real privacy defect, not just a bug) or
 * silently drops every event after opt-in is granted late in a session.
 *
 * Also guards `emitEvent`'s pass-through of the underlying `SharedFlow.tryEmit` result, which the
 * eventPool's own comment says is expected to return false under a full buffer — a caller that
 * cannot see that return value would never know an event was dropped.
 *
 * Robolectric, not plain JUnit: `emitEvent` now opens an `android.os.Trace` section
 * (`Intempt.trackEnqueue`), and the stub `android.jar` throws "Stub!" from every method on it.
 * Robolectric is only supplying a real `Trace` here — it is not being trusted for anything
 * API-level (see CLAUDE.md §2).
 */
@RunWith(RobolectricTestRunner::class)
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

    private fun pool(dispatcher: CoroutineDispatcher) =
        EventPoolManagerService(
            config = config,
            logger = logger,
            http = http,
            intemptEvent = intemptEvent,
            delivery = delivery,
            dispatcher = dispatcher,
        )

    private fun filler() = IntemptEvent(name = "filler", type = "track", payload = emptyArray())

    /**
     * INT-5964 review: the push-token release on logOut()/reset() went through [emitEvent], whose
     * `tryEmit` drops on a full buffer, so a burst just before a logout could cost the one event
     * that takes the logged-out user's token off their profile. The buffer here is genuinely full
     * (a subscriber that has not drained, as a busy collector would be), shown by [emitEvent]
     * returning false, and the release must still reach the durable queue.
     */
    @Test
    fun `enqueueDurably reaches the durable queue while the in-memory buffer is full`() {
        val scheduler = TestCoroutineScheduler()
        val service = pool(StandardTestDispatcher(scheduler))
        scheduler.runCurrent() // the collector subscribes and waits; nothing drains from here on

        var filled = false
        repeat(BUFFER_FILL_LIMIT) { if (!filled && !service.emitEvent(filler())) filled = true }
        assertTrue("precondition: the buffer is full and emitEvent drops", filled)
        assertFalse("precondition: still full", service.emitEvent(filler()))

        service.enqueueDurably("App install/upgrade", "installUpgrade") { emptyArray() }
        scheduler.runCurrent()

        verify(delivery).enqueueEvent(argThat { getString("name") == "App install/upgrade" })
    }

    /** The payload build (PackageManager, `BuildConfig`) must not run on the caller's thread. */
    @Test
    fun `enqueueDurably builds the payload on the pool's dispatcher, not the caller's thread`() {
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "pool-test") }
        try {
            val service = pool(executor.asCoroutineDispatcher())
            var builtOn: Thread? = null

            val job =
                service.enqueueDurably("App install/upgrade", "installUpgrade") {
                    builtOn = Thread.currentThread()
                    emptyArray()
                }
            runBlocking { job.join() }

            assertNotNull(builtOn)
            assertNotEquals(Thread.currentThread(), builtOn)
            assertEquals("pool-test", builtOn?.name)
            val queued = argumentCaptor<JSONObject>()
            verify(delivery).enqueueEvent(queued.capture())
            assertEquals("installUpgrade", queued.firstValue.getString("type"))
        } finally {
            executor.shutdownNow()
        }
    }

    /** Nothing retries a failed release, so the failure must be loud. */
    @Test
    fun `enqueueDurably logs an error and queues nothing when the build fails`() {
        val service = pool(UnconfinedTestDispatcher())

        service.enqueueDurably("App install/upgrade", "installUpgrade") { error("no package info") }

        verify(delivery, never()).enqueueEvent(any())
        verify(logger).error(argThat { contains("App install/upgrade") && contains("NOT queued") })
    }

    private companion object {
        /** replay 10 + extraBufferCapacity 256, with headroom. */
        const val BUFFER_FILL_LIMIT = 400
    }
}
