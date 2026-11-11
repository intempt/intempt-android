@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core.services.eventPool

import com.intempt.core.services.LoggerManagerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

// flush, discardQueuedEvents and enqueueDurably each reach the delivery worker in the
// order they were called relative to recorded events: an op runs once the pool collector
// has handed off every event accepted before it (INT-7447 for flush; Ro-bot
// mcp-1794398578-18 impact:F1 for the discard, which used to overtake both).
//
// The collector runs due ops itself, under the lane lock, between two hand-offs. A separate lane
// that waits for a count and then posts cannot do this: SharedFlow.collect does not suspend
// between buffered values, so the collector posts the next event (recorded after a reset)
// before the waiting lane wakes, and the reset discards it.
//
// Counts, not "pending == 0": under autocapture the collector is rarely idle, so a discard
// waiting for idle would stall and then throw away the new profile's events.
// Only the pool collector counts hand-offs: SessionTracker is a second subscriber and must not.
internal class OrderedDeliveryLane(
    private val scope: CoroutineScope,
    private val logger: LoggerManagerService,
    // A collector that died or stalls must cost one late op, never one that does not happen.
    private val waitBoundMs: Long,
) {
    private val acceptedCount = AtomicLong(0)
    private val lock = Any()
    private var handedOffCount = 0L
    private val ops = ArrayDeque<Op>()

    class Op internal constructor(
        internal val target: Long,
        internal var action: (() -> Unit)?,
    ) {
        internal var expired = false
        internal var done = false
        internal var abandoned = false
        internal var timer: Job? = null
    }

    /** An emit the flow accepted. A refused emit is never counted, so nothing waits for it. */
    fun accepted() {
        acceptedCount.incrementAndGet()
    }

    /** Runs the pool collector's hand-off under the lock, so no op can interleave with it. */
    fun handOff(block: () -> Unit) = synchronized(lock) { block() }

    /** Called by the pool collector once per collected event, consent included. */
    fun handedOff() =
        synchronized(lock) {
            handedOffCount++
            runDue()
        }

    /** Runs [action] after every event accepted before this call has been handed off. */
    fun afterRecordedEvents(action: () -> Unit): Op = enqueue(afterEvents = true, action = action)

    /** Takes a place behind every earlier op now, for an action that is only known later. */
    fun reserve(): Op = enqueue(afterEvents = false, action = null)

    fun fill(
        op: Op,
        action: () -> Unit,
    ) = synchronized(lock) {
        if (op.abandoned) {
            run(op, action)
        } else {
            op.action = action
            runDue()
        }
    }

    private fun enqueue(
        afterEvents: Boolean,
        action: (() -> Unit)?,
    ): Op {
        val op =
            synchronized(lock) {
                // Read inside the lock so targets never decrease along the queue.
                Op(if (afterEvents) acceptedCount.get() else 0L, action).also {
                    ops.addLast(it)
                    runDue()
                }
            }
        if (!op.done) {
            val timer =
                scope.launch {
                    delay(waitBoundMs)
                    synchronized(lock) {
                        for (queued in ops) {
                            queued.expired = true
                            if (queued === op) break
                        }
                        runDue()
                    }
                }
            synchronized(lock) { if (op.done || op.abandoned) timer.cancel() else op.timer = timer }
        }
        return op
    }

    // Caller holds the lock. Every action only posts a Message to the delivery worker.
    private fun runDue() {
        while (true) {
            val head = ops.firstOrNull() ?: return
            val action = head.action
            when {
                action == null && head.expired -> {
                    // A reserve() whose action outlived the bound: it runs on its own when filled,
                    // rather than holding every op behind it.
                    ops.removeFirst()
                    head.abandoned = true
                    head.timer?.cancel()
                }
                action != null && (head.expired || handedOffCount >= head.target) -> {
                    ops.removeFirst()
                    run(head, action)
                }
                else -> return
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // a failed post must not wedge every op behind it
    private fun run(
        op: Op,
        action: () -> Unit,
    ) {
        op.done = true
        op.timer?.cancel()
        try {
            action()
        } catch (e: Exception) {
            logger.error("EventPool | ordered delivery op failed: ${e.message}")
        }
    }
}
