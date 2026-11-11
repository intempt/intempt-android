package com.intempt.core.queue

/**
 * Exposes [HttpStatusPolicy.isConsentRetryable] (package-private Java) to the Kotlin services. The
 * consent drain keeps its own retry taxonomy since INT-7436 split it from the event queue's
 * (plan2/android.md decision (c)); both still live in HttpStatusPolicy so the two are read side by
 * side (CLAUDE.md sections 5 and 6).
 */
internal fun isStatusRetryable(status: Int): Boolean = HttpStatusPolicy.isConsentRetryable(status)
