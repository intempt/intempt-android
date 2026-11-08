package com.intempt.core.queue

/**
 * Exposes [HttpStatusPolicy.isRetryable] (package-private Java) to the Kotlin services, so the
 * consent drain uses the same retry-vs-drop taxonomy as the event queue instead of a second list
 * (CLAUDE.md sections 5 and 6).
 */
internal fun isStatusRetryable(status: Int): Boolean = HttpStatusPolicy.isRetryable(status)
