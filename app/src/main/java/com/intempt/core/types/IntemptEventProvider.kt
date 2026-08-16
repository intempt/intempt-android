package com.intempt.core.types

internal interface IntemptEventProvider {
    val eventId: String
    val sessionId: String
    val pageId: String
    val profileId: String
    val timestamp: Long

    fun getEventTime(): Long

    fun toFormated(): Map<String, Any?>
}
