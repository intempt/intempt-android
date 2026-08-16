package com.intempt.core.eventModels
import com.intempt.core.types.IntemptEventProvider

internal data class ScreenViewEvent(
    override val eventId: String,
    override val sessionId: String,
    override val pageId: String,
    override val profileId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    private val activity: String,
    private val fullActivity: String,
    private val screenName: String,
    private val timeOnScreen: Long?,
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
                    "activity" to activity,
                    "fullActivity" to fullActivity,
                    "screenName" to screenName,
                    "timeOnScreen" to timeOnScreen,
                ),
        )
    }

    override fun toString(): String {
        val timeOnScreenString = timeOnScreen?.let { "timeOnScreen: $timeOnScreen," } ?: ""

        val output = """
            {
                sessionId: $sessionId,
                eventId: $eventId,
                pageId: $pageId,
                profileId: $profileId,
                timestamp: $timestamp,
                data: {
                    activity: $activity,
                    fullActivity: $fullActivity,
                    screenName: $screenName,
                    $timeOnScreenString
                },
            }
        """
        return output.trimIndent()
    }
}
