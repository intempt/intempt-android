package com.intempt.core.eventModels
import com.intempt.core.services.StorageManagerService
import com.intempt.core.services.generateId
import javax.inject.Inject


internal open class BaseIntemptEvent(
    private val eventType:String = ""
) {

    object storage {
        fun getSessionId(): String {
            return "-001"
        }
        fun getPageId(): String {
            return "-001"
        }
        fun getProfileId(): String {
            return "-001"
        }

        fun getFragmentName(name:String): String{
            return "-001"
        }
        fun getPreviousVersionCode(): Int{
            return -1
        }
        fun getPageTime(): Int{
            return -1
        }
//        fun getPreviousBuildType(): String{
//            return "-001"
//        }
//        fun getAppVisibilityState(): AppVisibilityState {
//            return  "Foreground" as AppVisibilityState
//        }
    }

    protected val eventId: String = generateId("ev");
    protected val sessionId: String = storage.getSessionId()
    protected val pageId: String = storage.getPageId()
    protected val profileId: String = storage.getProfileId()
    protected val timestamp: Long = System.currentTimeMillis();




    fun getEventType():String{
        return eventType
    }

    fun getEventTime(): Long {
        return timestamp;
    }

    open fun toFormatted(): Map<String, Any> {
        return mapOf(
            "sessionId" to sessionId,
            "eventId" to eventId,
            "pageId" to pageId,
            "profileId" to profileId,
            "timestamp" to timestamp
        )
    }





}