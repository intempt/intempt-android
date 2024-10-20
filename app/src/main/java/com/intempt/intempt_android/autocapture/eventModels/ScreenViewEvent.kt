package com.intempt.intempt_android.autocapture.eventModels
import com.intempt.intempt_android.types.ScreenViewProps
import com.intempt.intempt_android.StorageHandler


class ScreenViewEvent(props: ScreenViewProps): BaseIntemptEvent() {
    private val activity = props.activity.localClassName ?: "";
    private val fullActivity = props.activity.javaClass.name ?: "";
    private val screenName = props.activity.javaClass.name ?: "";
    private var timeOnScreen:Long? = null;


    init{
        if (props.entityName == "screenLeave") {
            val durationInSeconds = (System.currentTimeMillis() - StorageHandler.getPageTime()) / 1000
            this.timeOnScreen = durationInSeconds
        }
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
                    activity: ${activity},
                    fullActivity: ${fullActivity},
                    screenName: ${screenName},
                    $timeOnScreenString
                },
            }
        """
        return output.trimIndent()
    }
}