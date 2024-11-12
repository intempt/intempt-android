package com.intempt.core.eventModels

import com.intempt.core.types.IntemptEventProvider


internal class IntemptEvent(
    val name: String,
    val type: String,
    val payload: Array<IntemptEventProvider>,
) {

    fun getEventType(): String {
        return type
    }

    fun getEventTimestamp(): Long {
        return payload[0].getEventTime()
    }

    fun getEventName(): String {
        return name
    }

    fun toFormated(): Map<String, Any?>{
        val formattedPayload = payload.map { it.toFormated() }

        return mapOf(
            "name" to name,
            "type" to type,
            "payload" to formattedPayload
        )
    }

    override fun toString(): String {
        val formattedPayload = payload.joinToString(
            separator = ",\n    ",
            prefix = "[\n    ",
            postfix = "\n  ]"
        ) { it.toFormated().toString() }

        return """
        {
            "name": "$name",
            "type": "$type",
            "payload": $formattedPayload
        }
    """.trimIndent()
    }
}











