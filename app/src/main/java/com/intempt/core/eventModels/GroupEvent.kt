package com.intempt.core.eventModels

import com.intempt.core.types.IntemptEventProvider

internal open class GroupEvent(
    override val eventId: String,
    override val sessionId: String,
    override val pageId: String,
    override val profileId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    private val accountId: String,
    private val accountAttributes: Map<String, Any>?,
) : IntemptEventProvider {
    override fun getEventTime(): Long {
        return timestamp;
    }

    override fun toFormated(): Map<String, Any?> {
        val map = mutableMapOf<String, Any>()
        map["sessionId"] = sessionId
        map["eventId"] = eventId
        map["pageId"] = pageId
        map["profileId"] = profileId
        map["timestamp"] = timestamp
        map["accountId"] = accountId
        accountAttributes?.let { map["accountAttributes"] = it }

        return map
    }

}