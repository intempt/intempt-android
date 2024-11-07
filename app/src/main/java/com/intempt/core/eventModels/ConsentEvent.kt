package com.intempt.core.eventModels

import com.intempt.core.types.IntemptEventProvider

internal open class ConsentEvent(
    override val eventId: String,
    override val sessionId: String,
    override val pageId: String,
    override val profileId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    private val action: String,
    private val email: String? = null,
    private val message: String? = null,
    private val category: String?= null,
    private val sourceId: String,
    private val validUntil: Long,
) : IntemptEventProvider {
    override fun getEventTime(): Long {
        return timestamp;
    }

    override fun toFormated(): Map<String, Any?> {
        val map = mutableMapOf<String, Any>()
//        map["sessionId"] = sessionId
//        map["eventId"] = eventId
//        map["pageId"] = pageId
        map["profileId"] = profileId
        map["timestamp"] = timestamp
        map["sourceId"] = sourceId
        map["source"] = "android"
        map["validUntil"] = validUntil
        map["action"] = action

        email?.let { map["email"] = it }
        message?.let { map["message"] = it }
        category?.let { map["category"] = it }


        return map
    }
}