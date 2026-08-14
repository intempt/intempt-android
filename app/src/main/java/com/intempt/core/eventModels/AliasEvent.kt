package com.intempt.core.eventModels

import com.intempt.core.types.IntemptEventProvider

internal class AliasEvent(
    override val eventId: String,
    override val sessionId: String,
    override val pageId: String,
    override val profileId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    private val userId: String,
    private val anotherUserId: String,
) : IntemptEventProvider {
    override fun getEventTime(): Long {
        return timestamp
    }

    override fun toFormated(): Map<String, Any> {
        return mapOf(
            "sessionId" to sessionId,
            "eventId" to eventId,
            "pageId" to pageId,
            "profileId" to profileId,
            "timestamp" to timestamp,
            "userId" to userId,
            "anotherUserId" to anotherUserId,
        )
    }
}
