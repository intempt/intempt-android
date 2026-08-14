package com.intempt.core.eventModels
import com.intempt.core.types.IntemptEventProvider

internal data class FragmentTransitionEvent(
    override val eventId: String,
    override val sessionId: String,
    override val pageId: String,
    override val profileId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    private val visibleFragment: String,
    private val addedFragment: String,
    private val removedFragment: String,
) : IntemptEventProvider {
    override fun toString(): String {
        val output = """
            {
                sessionId: $sessionId,
                eventId: $eventId,
                pageId: $pageId,
                profileId: $profileId,
                timestamp: $timestamp,
                data: {
                    visibleFragment: $visibleFragment,
                    addedFragment: $addedFragment,
                    removedFragment: $removedFragment,
                },
            }
        """
        return output.trimIndent()
    }

    override fun toFormated(): Map<String, Any> {
        return mapOf(
            "sessionId" to sessionId,
            "eventId" to eventId,
            "pageId" to pageId,
            "profileId" to profileId,
            "timestamp" to timestamp,
            "data" to
                mapOf(
                    "visibleFragment" to visibleFragment,
                    "addedFragment" to addedFragment,
                    "removedFragment" to removedFragment,
                ),
        )
    }

    override fun getEventTime(): Long {
        return timestamp
    }
}
