@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core

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
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
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

    @Before
    fun setUp() {
        delivery = mock(DeliveryMessages::class.java)
        doAnswer {
            calls.add("enqueue")
            null
        }.whenever(delivery).enqueueEvent(any())
        doAnswer { inv ->
            val enqueued = calls.count { it == "enqueue" }
            calls.add("flush")
            inv.getArgument<FlushCompletion?>(0)?.onFlushed(enqueued)
            null
        }.whenever(delivery).flush(any<FlushCompletion>())
        doAnswer {
            calls.add("flush")
            null
        }.whenever(delivery).flush()
    }

    private fun service(dispatcher: CoroutineDispatcher): EventPoolManagerService {
        val config = mock(ConfigManagerService::class.java)
        whenever(config.isUserOptIn).thenReturn(true)
        return EventPoolManagerService(
            config,
            mock(LoggerManagerService::class.java),
            mock(HttpManagerService::class.java),
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
}
