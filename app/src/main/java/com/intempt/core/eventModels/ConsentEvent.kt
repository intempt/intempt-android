package com.intempt.core.eventModels

import com.intempt.core.types.IntemptEventProvider

internal open class ConsentEvent(
    override val eventId: String,
    override val sessionId: String,
    override val pageId: String,
    override val profileId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    action: String?,
    email: String? = null,
    message: String? = null,
    category: String?= null,
    sourceId: String,
    validUntil: Long,
) : IntemptEventProvider {
    override fun getEventTime(): Long {
        return timestamp;
    }

    override fun toFormatted(): Map<String, Any> {
        return mapOf(
            "sessionId" to sessionId,
            "eventId" to eventId,
            "pageId" to pageId,
            "profileId" to profileId,
        )
    }
}