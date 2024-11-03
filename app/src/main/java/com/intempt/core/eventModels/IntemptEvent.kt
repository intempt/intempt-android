package com.intempt.core.eventModels

import com.intempt.core.types.IntemptEventProvider


internal class IntemptEvent(
    val name: String,
    private val type: String,
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

    fun toFormated(): Map<String, Any>{
        val formattedPayload = payload.map { it.toFormatted() }

        return mapOf(
            "name" to name,
            "type" to type,
            "payload" to formattedPayload
        )
    }

    override fun toString(): String {
        // Convert toFormatted() to a JSON-like string
        val formattedPayload = payload.joinToString(
            separator = ",\n    ", // Adds indentation for readability
            prefix = "[\n    ",
            postfix = "\n  ]"
        ) { it.toFormatted().toString() }

        return """
        {
            "name": "$name",
            "type": "$type",
            "payload": $formattedPayload
        }
    """.trimIndent()
    }
}











