package com.intempt.core.queue

import kotlin.random.Random

/**
 * Backoff for resending pending consent decisions. Same numbers as the Swift SDK's RetryPolicy:
 * engaged after [FAILURES_TILL_BACKOFF] consecutive failures, then 2^(n-1) * 60 s plus jitter,
 * clamped to 60..600 s, and never shorter than a server-supplied Retry-After. The clock and the
 * jitter are injected so every branch can be tested without waiting.
 */
internal class ConsentRetryPolicy(
    private val now: () -> Long = System::currentTimeMillis,
    private val jitterMs: () -> Long = { Random.nextLong(JITTER_BOUND_MS) },
) {
    private var allowedAfter: Long = now()
    var consecutiveFailures: Int = 0
        private set

    /** True while a backoff window is open. */
    val requestNotAllowed: Boolean get() = now() < allowedAfter

    fun recordSuccess() {
        consecutiveFailures = 0
        allowedAfter = now()
    }

    fun recordFailure(retryAfterMs: Long? = null) {
        consecutiveFailures += 1
        var wait = retryAfterMs ?: 0L
        if (consecutiveFailures >= FAILURES_TILL_BACKOFF) {
            wait = maxOf(wait, backoffMs(consecutiveFailures))
        }
        allowedAfter = now() + wait
    }

    fun backoffMs(failureCount: Int): Long {
        val exponent = (failureCount - 1).coerceIn(0, MAX_EXPONENT)
        val raw = (1L shl exponent) * BASE_MS + jitterMs()
        return raw.coerceIn(MIN_BACKOFF_MS, MAX_BACKOFF_MS)
    }

    companion object {
        const val FAILURES_TILL_BACKOFF = 2
        const val MIN_BACKOFF_MS = 60_000L
        const val MAX_BACKOFF_MS = 600_000L
        private const val BASE_MS = 60_000L
        private const val JITTER_BOUND_MS = 30_000L
        private const val MAX_EXPONENT = 20
    }
}
