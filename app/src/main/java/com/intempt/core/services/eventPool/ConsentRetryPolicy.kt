package com.intempt.core.services.eventPool

import com.intempt.core.queue.isStatusRetryable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    /** Milliseconds until the open backoff window closes; 0 when none is open. */
    fun remainingMs(): Long = (allowedAfter - now()).coerceAtLeast(0L)

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

/**
 * Fail safe (CLAUDE.md section 6): a consent status is kept and retried only when the event
 * queue's `HttpStatusPolicy.isRetryable` says a later attempt can plausibly succeed. This
 * delegates to it, so the two cannot drift. Anything it rejects (the other 4xx, 3xx, a status
 * above 599) counts as a rejection of that record and is dropped after MAX_CONSENT_REJECTIONS
 * consecutive answers, so an unrecognised status loses one consent instead of blocking every
 * later one. A consent kept by this policy is still bounded: the drain drops it once it is older
 * than the maximum consent age.
 */
internal fun isConsentRetryable(status: Int): Boolean = isStatusRetryable(status)

/**
 * Runs [block] once after [delayMs], unless [existing] is still pending or the delay is not
 * positive (nothing is waiting). Returns the job to remember. Cancellation is rethrown, any other
 * failure goes to [onError].
 */
@Suppress("TooGenericExceptionCaught") // a failed background drain must be logged, never crash the host
internal fun CoroutineScope.launchOnceAfter(
    existing: Job?,
    delayMs: Long,
    onError: (Exception) -> Unit,
    block: suspend () -> Unit,
): Job? =
    if (delayMs <= 0L || existing?.isActive == true) {
        existing
    } else {
        launch {
            delay(delayMs)
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
            }
        }
    }
