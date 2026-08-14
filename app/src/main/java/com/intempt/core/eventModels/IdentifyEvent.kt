package com.intempt.core.eventModels

import com.intempt.core.types.IntemptEventProvider

internal open class IdentifyEvent(
    override val eventId: String,
    override val sessionId: String,
    override val pageId: String,
    override val profileId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    private val userId: String,
    private val userAttributes: Map<String, String>?,
    private val data: Map<String, String>?,
) : IntemptEventProvider {
    override fun getEventTime(): Long {
        return timestamp
    }

    override fun toFormated(): Map<String, Any> {
        val map = mutableMapOf<String, Any>()

        map["sessionId"] = sessionId
        map["eventId"] = eventId
        map["pageId"] = pageId
        map["profileId"] = profileId
        map["timestamp"] = timestamp
        map["userId"] = userId

        userAttributes?.let { map["userAttributes"] = it }
        data?.let { map["data"] = it }

        return map
    }
}
