package com.intempt.core.eventModels

internal class IntemptEvent(
    private val name: String,
    private val type: String,
    private val payload: Array<BaseIntemptEvent>,
) {

    fun getEventType(): String {
        return type
    }

    fun getEventTimestamp(): Long {
        return payload[0].getEventTime()
    }



}

internal open class IdentifyEvent(
    private val userId: String,
    private val userAttributes: Map<String, String>?,
    private val data: Map<String, String>?
) : BaseIntemptEvent() {}

internal open class GroupEvent(
    private val accountId: String,
    private val accountAttributes: Map<String, String>?,
) : BaseIntemptEvent() {}

internal open class TrackEvent(
    private val data: Map<String, String>,
) : BaseIntemptEvent() {}

internal open class RecordEvent(
    accountId: String?,
    userId: String?,
    accountAttributes: Map<String, String>?,
    userAttributes: Map<String, String>?,
    data: Map<String, String>?
) : BaseIntemptEvent() {}