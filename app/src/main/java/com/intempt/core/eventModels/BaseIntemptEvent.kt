package com.intempt.core.eventModels

internal data class BaseIntemptEvent(
    val eventId: String,
    val sessionId: String,
    val pageId: String,
    val profileId: String,
    val timestamp: Long = System.currentTimeMillis(),
)
