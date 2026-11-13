package com.intempt.core.queue

/*
 * The consent drain's send-once decisions (R3, Beso 2026-11-13), next to the event queue's so the
 * two read the same rule: HttpStatusPolicy is package-private Java, and this file is the Kotlin
 * services' way in. Pure, so the mutation module compiles and gates it.
 */

private const val MILLIS_PER_SECOND = 1_000L

/**
 * The failure kind for a consent POST's final outcome, or null when it was delivered. [status] is
 * 0 when no response arrived; [notSent] says the request never reached the server.
 */
internal fun consentFailureKind(
    status: Int,
    notSent: Boolean,
): DeliveryFailure.Kind? =
    when {
        status in HTTP_OK_FIRST..HTTP_OK_LAST -> null
        status > 0 -> DeliveryFailure.Kind.TERMINAL
        notSent -> DeliveryFailure.Kind.TRANSPORT
        else -> DeliveryFailure.Kind.DROPPED
    }

/** Milliseconds before the one consent resend, or -1 for none: the event transport's rule. */
internal fun consentResendDelayMs(
    status: Int,
    retryAfterMs: Long?,
): Long {
    val seconds = HttpStatusPolicy.resendDelaySeconds(status, retryAfterSeconds(retryAfterMs))
    return if (seconds < 0) -1L else seconds * MILLIS_PER_SECOND
}

/** A Retry-After in whole seconds, rounded up so a wait is never cut short; 0 when there was none. */
internal fun retryAfterSeconds(retryAfterMs: Long?): Int =
    if (retryAfterMs == null || retryAfterMs <= 0L) {
        0
    } else {
        ((retryAfterMs - 1) / MILLIS_PER_SECOND + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

private const val HTTP_OK_FIRST = 200
private const val HTTP_OK_LAST = 299
