package com.intempt.core.eventModels
import com.intempt.core.types.IntemptEventProvider

internal class FragmentTransitionEvent(
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

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FragmentTransitionEvent) return false
        return eventId == other.eventId &&
            sessionId == other.sessionId &&
            pageId == other.pageId &&
            profileId == other.profileId &&
            timestamp == other.timestamp &&
            visibleFragment == other.visibleFragment &&
            addedFragment == other.addedFragment &&
            removedFragment == other.removedFragment
    }

    override fun hashCode(): Int {
        var result = eventId.hashCode()
        result = 31 * result + sessionId.hashCode()
        result = 31 * result + pageId.hashCode()
        result = 31 * result + profileId.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + visibleFragment.hashCode()
        result = 31 * result + addedFragment.hashCode()
        result = 31 * result + removedFragment.hashCode()
        return result
    }
}
