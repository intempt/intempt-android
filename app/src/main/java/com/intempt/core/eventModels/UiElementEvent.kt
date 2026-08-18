package com.intempt.core.eventModels
import com.intempt.core.types.IntemptEventProvider

internal class UiElementEvent(
    override val eventId: String,
    override val sessionId: String,
    override val pageId: String,
    override val profileId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    private val targetElement: String,
    private val hierarchy: String,
    private val targetText: String,
    private val targetValue: String,
    private val targetClass: String,
    private val targetId: String,
    private val fullTargetId: String,
) : IntemptEventProvider {
    override fun getEventTime(): Long {
        return timestamp
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
                    "targetElement" to targetElement,
                    "hierarchy" to hierarchy,
                    "targetText" to targetText,
                    "targetClass" to targetClass,
                    "targetValue" to targetValue,
                    "targetId" to targetId,
                    "fullTargetId" to fullTargetId,
                ),
        )
    }

    override fun toString(): String {
        val output = """
            {
                sessionId: $sessionId,
                eventId: $eventId,
                pageId: $pageId,
                profileId: $profileId,
                timestamp: $timestamp,
                data: {
                    targetElement: $targetElement,
                    hierarchy: $hierarchy,
                    targetText: $targetText,
                    targetClass: $targetClass,
                    targetValue: $targetValue,
                    targetId: $targetId,
                    fullTargetId: $fullTargetId,
                },
            }
        """
        return output.trimIndent()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UiElementEvent) return false
        return eventId == other.eventId &&
            sessionId == other.sessionId &&
            pageId == other.pageId &&
            profileId == other.profileId &&
            timestamp == other.timestamp &&
            targetElement == other.targetElement &&
            hierarchy == other.hierarchy &&
            targetText == other.targetText &&
            targetValue == other.targetValue &&
            targetClass == other.targetClass &&
            targetId == other.targetId &&
            fullTargetId == other.fullTargetId
    }

    override fun hashCode(): Int {
        var result = eventId.hashCode()
        result = 31 * result + sessionId.hashCode()
        result = 31 * result + pageId.hashCode()
        result = 31 * result + profileId.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + targetElement.hashCode()
        result = 31 * result + hierarchy.hashCode()
        result = 31 * result + targetText.hashCode()
        result = 31 * result + targetValue.hashCode()
        result = 31 * result + targetClass.hashCode()
        result = 31 * result + targetId.hashCode()
        result = 31 * result + fullTargetId.hashCode()
        return result
    }
}
