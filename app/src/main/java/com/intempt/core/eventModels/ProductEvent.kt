package com.intempt.core.eventModels

import com.intempt.core.types.IntemptEventProvider

internal class ProductEvent(
    override val eventId: String,
    override val sessionId: String,
    override val pageId: String,
    override val profileId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    private val productId: String,
    private val quantity: Int?,
) : IntemptEventProvider {
    override fun getEventTime(): Long {
        return timestamp;
    }

    override fun toFormated(): Map<String, Any?> {
        val map = mutableMapOf<String, Any>()
        val dataMap = mutableMapOf<String, Any>()

        dataMap["productId"] = productId
        quantity?.let { dataMap["quantity"] = it }

        map["sessionId"] = sessionId
        map["eventId"] = eventId
        map["pageId"] = pageId
        map["profileId"] = profileId
        map["timestamp"] = timestamp
        map["data"] = dataMap

        return map
    }
}