@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core

import com.intempt.core.eventModels.ConsentEvent
import com.intempt.core.eventModels.IntemptEvent
import com.intempt.core.queue.DeliveryMessages
import com.intempt.core.queue.DeliveryMessages.FlushCompletion
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.HttpManagerService
import com.intempt.core.services.IntemptEventManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.eventPool.EventPoolManagerService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext

/**
 * INT-7447 (audit K12): `record()` followed at once by `flush()` must deliver that event.
 *
 * record() ends in emitEvent, which hands the event to a collector coroutine; only that collector
 * calls DeliveryMessages.enqueueEvent. flush() used to post to the delivery worker on the
 * caller's thread, so it reached the worker before the enqueue and flushed an empty queue.
 *
 * StandardTestDispatcher, not Unconfined: the race only exists while the collector has not yet
 * run, and a standard dispatcher holds it there until the scheduler advances.
 *
 * The fake delivery answers a flush with the number of events enqueued before it, which is what
 * the worker's FIFO handler would report for a 200.
 */
@RunWith(RobolectricTestRunner::class)
class FlushOrderingTest {
    private lateinit var delivery: DeliveryMessages
    private val calls = CopyOnWriteArrayList<String>()

    // What the fake worker holds: enqueue appends, emptyQueue clears, flush reports and sends it.
    private val queued = CopyOnWriteArrayList<String>()
    private lateinit var http: HttpManagerService

    @Before
    fun setUp() {
        delivery = mock(DeliveryMessages::class.java)
        http = mock(HttpManagerService::class.java)
        doAnswer { inv ->
            calls.add("enqueue")
            queued.add(inv.getArgument<JSONObject>(0).optString("name"))
            null
        }.whenever(delivery).enqueueEvent(any())
        doAnswer { inv ->
            val enqueued = queued.size
            calls.add("flush")
            sent.addAll(queued)
            queued.clear()
            inv.getArgument<FlushCompletion?>(0)?.onFlushed(enqueued)
            null
        }.whenever(delivery).flush(any<FlushCompletion>())
        doAnswer {
            calls.add("flush")
            sent.addAll(queued)
            queued.clear()
            null
        }.whenever(delivery).flush()
        doAnswer {
            calls.add("empty")
            queued.clear()
            null
        }.whenever(delivery).emptyQueue()
    }

    private val sent = CopyOnWriteArrayList<String>()

    private fun service(dispatcher: CoroutineDispatcher): EventPoolManagerService {
        val config = mock(ConfigManagerService::class.java)
        whenever(config.isUserOptIn).thenReturn(true)
        whenever(config.consentUrl).thenReturn("https://consent.test")
        return EventPoolManagerService(
            config,
            mock(LoggerManagerService::class.java),
            http,
            mock(IntemptEventManagerService::class.java),
            delivery,
            dispatcher = dispatcher,
        )
    }

    private fun event(name: String) = IntemptEvent(name = name, type = "custom", payload = emptyArray())

    @Test
    fun `flush right after record delivers the recorded event`() =
        runTest {
            val svc = service(StandardTestDispatcher(testScheduler))
            var delivered = -1

            svc.emitEvent(event("purchase"))
            svc.flush { delivered = it }
            testScheduler.advanceUntilIdle()

            assertEquals("the flush must see the event recorded just before it", 1, delivered)
            assertEquals(listOf("enqueue", "flush"), calls)
        }

    /**
     * Runs dispatched work in the order the test picks, so the flush coroutine can run before the
     * collector: the order Dispatchers.IO is free to choose, which a FIFO test dispatcher never does.
     */
    private class ManualDispatcher : CoroutineDispatcher() {
        val tasks = ArrayDeque<Runnable>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            tasks.addLast(block)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    @Test
    fun `flush waits for the hand-off even when it runs before the collector`() {
        val dispatcher = ManualDispatcher()
        val svc = service(dispatcher)
        var delivered = -1

        svc.emitEvent(event("purchase"))
        svc.flush { delivered = it }
        dispatcher.tasks.addFirst(dispatcher.tasks.removeLast())
        dispatcher.runAll()

        assertEquals("the flush must wait for the collector's enqueue", 1, delivered)
        assertEquals(listOf("enqueue", "flush"), calls)
    }

    @Test
    fun `flush without a completion still waits for the recorded events`() =
        runTest {
            val svc = service(StandardTestDispatcher(testScheduler))

            svc.emitEvent(event("a"))
            svc.emitEvent(event("b"))
            svc.flush()
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("enqueue", "enqueue", "flush"), calls)
        }

    /**
     * The collector cannot run before the scheduler advances, so the flow's buffer (10 replay + 256
     * extra) fills and the rest of the burst is refused by tryEmit. A refused event must not stay
     * counted as in flight, or this flush would wait the full bound for hand-offs that never come.
     */
    @Test
    fun `an event the flow refused is not waited for`() =
        runTest {
            val svc = service(StandardTestDispatcher(testScheduler))
            val accepted = (1..300).count { svc.emitEvent(event("burst $it")) }
            var delivered = -1

            svc.flush { delivered = it }
            testScheduler.advanceUntilIdle()

            assertEquals("the buffer must have refused part of the burst", true, accepted < 300)
            assertEquals(accepted, delivered)
            assertEquals("the flush must not wait out the bound", 0L, testScheduler.currentTime)
        }

    @Test
    fun `flush with nothing recorded is not delayed`() =
        runTest {
            val svc = service(StandardTestDispatcher(testScheduler))
            var delivered = -1

            svc.flush { delivered = it }
            testScheduler.advanceUntilIdle()

            assertEquals(0, delivered)
            assertEquals(0L, testScheduler.currentTime)
            assertEquals(listOf("flush"), calls)
        }

    /*
     * Ro-bot mcp-1794398578-18 impact:F1 on android#81. optOut()/reset() call discardQueuedEvents,
     * which used to post emptyQueue on the caller's thread while flush and every recorded event
     * reach the worker later, from the collector. So the discard overtook both: a flush issued
     * before an opt-out was emptied before it ran, and an event recorded before an opt-out was
     * enqueued after the discard and uploaded anyway.
     *
     * Each case runs under the FIFO test dispatcher and under ManualDispatcher with the newest
     * task moved to the front, which is the reordering Dispatchers.IO may pick.
     */
    private fun bothOrders(case: (EventPoolManagerService, () -> Unit) -> Unit) {
        val manual = ManualDispatcher()
        case(service(manual)) {
            if (manual.tasks.isNotEmpty()) manual.tasks.addFirst(manual.tasks.removeLast())
            manual.runAll()
        }
        calls.clear()
        queued.clear()
        sent.clear()
        runTest {
            case(service(StandardTestDispatcher(testScheduler))) { testScheduler.advanceUntilIdle() }
        }
    }

    @Test
    fun `an event recorded before an opt-out is never sent`() =
        bothOrders { svc, settle ->
            svc.emitEvent(event("before"))
            svc.discardQueuedEvents()
            settle()
            svc.flush()
            settle()

            assertEquals(listOf("enqueue", "empty", "flush"), calls)
            assertEquals(emptyList<String>(), sent)
        }

    @Test
    fun `a flush issued before an opt-out runs before the discard`() =
        bothOrders { svc, settle ->
            var delivered = -1
            svc.emitEvent(event("before"))
            svc.flush { delivered = it }
            svc.discardQueuedEvents()
            settle()

            assertEquals(listOf("enqueue", "flush", "empty"), calls)
            assertEquals(1, delivered)
            assertEquals(listOf("before"), sent)
        }

    /*
     * The discard waits for the events accepted when it was called, not for an idle collector:
     * under autocapture new events keep arriving, and an event recorded after the reset belongs
     * to the new profile and must survive it. A pre-reset event is what tells "waits for its
     * snapshot" apart from "posts at once".
     */
    @Test
    fun `an event recorded after a reset survives it`() =
        bothOrders { svc, settle ->
            svc.emitEvent(event("old profile"))
            svc.discardQueuedEvents()
            svc.emitEvent(event("new profile"))
            settle()
            svc.flush()
            settle()

            assertEquals(listOf("new profile"), sent)
        }

    /*
     * reset() discards, then hands the push-token release (INT-5964) to enqueueDurably. If the
     * discard waits for the collector and the release does not, the release is enqueued first and
     * emptied with the old profile's events: the token stays on the profile being left.
     */
    @Test
    fun `the push-token release queued after a reset's discard is kept`() =
        bothOrders { svc, settle ->
            svc.emitEvent(event("old profile"))
            svc.discardQueuedEvents()
            svc.enqueueDurably("release", "installUpgrade") { emptyArray() }
            settle()
            svc.flush()
            settle()

            assertEquals(listOf("release"), sent)
        }

    /*
     * consent(REJECT) emits the consent and then calls optOut(). The discard must not stop the
     * consent, which never enters the durable queue: the withdrawal is the one thing an opt-out
     * has to send.
     */
    @Test
    fun `a rejection consent still goes out when the opt-out follows it`() =
        bothOrders { svc, settle ->
            val consent =
                ConsentEvent(
                    eventId = "e1",
                    sessionId = "s1",
                    pageId = "p1",
                    profileId = "profile-1",
                    action = "reject",
                    sourceId = "src-1",
                    validUntil = System.currentTimeMillis() + 1000,
                )
            svc.emitEvent(IntemptEvent(name = "consent", type = "consent", payload = arrayOf(consent)))
            svc.discardQueuedEvents()
            settle()

            verifyBlocking(http) { postForStatus(any(), any<JSONObject>(), any()) }
            org.mockito.Mockito.clearInvocations(http)
        }
}
