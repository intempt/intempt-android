package com.intempt.core.eventModels

import com.intempt.core.types.IntemptEventProvider
import com.intempt.core.types.IntemptValue

internal open class TrackEvent(
    override val eventId: String,
    override val sessionId: String,
    override val pageId: String,
    override val profileId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    private val data: Map<String, IntemptValue>,
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
            "data" to IntemptValue.rawMap(data),
        )
    }
}
