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
        // A server-supplied Retry-After is capped: an absurd value must not park consents for the session.
        var wait = (retryAfterMs ?: 0L).coerceIn(0L, MAX_BACKOFF_MS)
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

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_REQUEST_TIMEOUT = 408
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR_FIRST = 500
private const val HTTP_SERVER_ERROR_LAST = 599

/**
 * Fail safe (CLAUDE.md section 6): a consent status is kept and retried only when a later attempt
 * can plausibly succeed with the same payload. Anything else that is not 2xx (the other 4xx, 3xx, a
 * status above 599) counts as a rejection of that record and is dropped after
 * MAX_CONSENT_REJECTIONS consecutive answers, so an unrecognised status loses one consent instead
 * of blocking every later one. Same default as HttpStatusPolicy.shouldDrop.
 */
internal fun isConsentRetryable(status: Int): Boolean =
    status <= 0 ||
        status == HTTP_UNAUTHORIZED ||
        status == HTTP_FORBIDDEN ||
        status == HTTP_REQUEST_TIMEOUT ||
        status == HTTP_TOO_MANY_REQUESTS ||
        status in HTTP_SERVER_ERROR_FIRST..HTTP_SERVER_ERROR_LAST
