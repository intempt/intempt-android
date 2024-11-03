package com.intempt.core.eventModels

import com.intempt.core.types.IntemptEventProvider

internal open class RecordEvent(
    override val eventId: String,
    override val sessionId: String,
    override val pageId: String,
    override val profileId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    private val accountId: String?,
    private val userId: String?,
    private val accountAttributes: Map<String, String>?,
    private val userAttributes: Map<String, String>?,
    private val data: Map<String, String>?
) : IntemptEventProvider {
    override fun getEventTime(): Long {
        return timestamp;
    }

    override fun toFormatted(): Map<String, Any> {
        val map = mutableMapOf<String, Any>()

        map["sessionId"] = sessionId
        map["eventId"] = eventId
        map["pageId"] = pageId
        map["profileId"] = profileId
        map["timestamp"] = timestamp

        accountId?.let { map["accountId"] = it }
        userId?.let { map["userId"] = it }
        accountAttributes?.let { map["accountAttributes"] = it }
        userAttributes?.let { map["userAttributes"] = it }
        data?.let { map["data"] = it }

        return map


    }
}