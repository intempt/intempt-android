package com.intempt.core.customCapture

import com.intempt.core.autocapture.BaseComponent
import com.intempt.core.services.Logger
import com.intempt.core.types.AutoCaptureParam
import com.intempt.core.types.Constants
import com.intempt.core.types.DispatchEventProps
import com.intempt.core.types.ScreenEventProps
import com.intempt.core.types.UiEventProps
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class CustomCaptureService @Inject constructor(
    //private val eventPool: EventPool
): BaseComponent(){
    internal fun autoCapture(listenerType: String, param: AutoCaptureParam){
        Logger.log("autoCapture | Is $listenerType listener")

        val payload: DispatchEventProps = when (param) {
            is UiEventProps -> onUiEventReceive(param)
            is ScreenEventProps -> onScreenEventReceive(param)
        }

       // eventPool.dispatchEvent(payload)
    }

    private  fun onUiEventReceive(
        props: UiEventProps
    ): DispatchEventProps {
        Logger.log("autoCapture | Is UiEventProps")
        val (activity, view, listenerType) = props;

        val eventName = when (listenerType) {
            "change" -> Constants.CHANGE.EVENT_NAME
            else -> Constants.TOUCH.EVENT_NAME

        }

        val entityName = when (listenerType) {
            "change" -> Constants.CHANGE.ENTITY_NAME
            else -> Constants.TOUCH.ENTITY_NAME
        }

        return DispatchEventProps(
            eventName = eventName,
            entityName = entityName,
            type = listenerType,
            event = null,
            context = activity,
            view = view
        )
    }

    private fun onScreenEventReceive(props:ScreenEventProps):DispatchEventProps{
        val (activity,eventName,entityName, eventType) = props
        return DispatchEventProps(
            eventName = eventName,
            entityName = entityName,
            type = eventType,
            event = null,
            context = activity
        )
    }

}




//
//    fun optIn() {
//        this.doNotTrack = false
//    }
//
//    fun optOut() {
//        this.doNotTrack = true
//    }
//
//    fun identify(params: IdentifyParams) {
//        if (!isUserOptIn()) return
//        if (!isIdentifyValid(params)) return
//
//        val profileId = getProfileId()
//        val sessionId = getSessionId()
//        val pageId = getPageId()
//
//        val eventData = IdentifyModel(
//            params,
//            profileId,
//            sessionId,
//            pageId
//        )
//
//        dispatchIntemptEvent("intempt:identify", mapOf("eventName" to eventData.name))
//        dispatchIntemptEvent("intempt:event", mapOf("event" to eventData))
//    }
//
//    fun group(params: GroupParams) {
//        if (!isUserOptIn()) return
//        if (!isGroupValid(params)) return
//
//        val profileId = getProfileId()
//        val sessionId = getSessionId()
//        val pageId = getPageId()
//
//        val eventData = GroupModel(
//            params,
//            profileId,
//            sessionId,
//            pageId
//        )
//
//        dispatchIntemptEvent("intempt:group", mapOf("eventName" to eventData.name))
//        dispatchIntemptEvent("intempt:event", mapOf("event" to eventData))
//    }
//
//    fun track(params: TrackParams) {
//        if (!isUserOptIn()) return
//        if (!isTrackValid(params)) return
//
//        val profileId = getProfileId()
//        val sessionId = getSessionId()
//        val pageId = getPageId()
//
//        val eventData = TrackModel(
//            params,
//            profileId,
//            sessionId,
//            pageId
//        )
//
//        dispatchIntemptEvent("intempt:track", mapOf("eventName" to eventData.name))
//        dispatchIntemptEvent("intempt:event", mapOf("event" to eventData))
//    }
//
//    fun record(params: RecordParams) {
//        if (!isUserOptIn()) return
//        if (!isRecordValid(params)) return
//
//        val profileId = getProfileId()
//        val sessionId = getSessionId()
//        val pageId = getPageId()
//
//        val eventData = RecordModel(
//            params,
//            profileId,
//            sessionId,
//            pageId
//        )
//
//        dispatchIntemptEvent("intempt:record", mapOf("eventName" to eventData.name))
//        dispatchIntemptEvent("intempt:event", mapOf("event" to eventData))
//    }
//
//    fun alias(params: AliasParams) {
//        if (!isUserOptIn()) return
//        if (!isAliasValid(params)) return
//
//        val profileId = getProfileId()
//
//        val eventData = AliasModel(
//            params,
//            profileId
//        )
//
//        dispatchIntemptEvent("intempt:alias", mapOf("eventName" to eventData.name))
//        dispatchIntemptEvent("intempt:event", mapOf("event" to eventData))
//    }
//
//    fun consent(params: ConsentParams) {
//        if (!isUserOptIn()) return
//        if (!isConsentValid(params)) return
//
//        val profileId = getProfileId()
//        val sourceId = config.sourceId
//        val pageId = getPageId()
//
//        val eventData = ConsentModel(
//            params,
//            profileId,
//            sourceId,
//            pageId
//        )
//
//        dispatchIntemptEvent("intempt:consent", mapOf("eventName" to eventData.name))
//        dispatchIntemptEvent("intempt:event", mapOf("event" to eventData))
//    }
//
//    private fun isUserOptIn(): Boolean {
//        // Implementation here
//        return true
//    }
//
//    private fun isIdentifyValid(params: IdentifyParams): Boolean {
//        // Implementation here
//        return true
//    }
//
//    private fun isGroupValid(params: GroupParams): Boolean {
//        // Implementation here
//        return true
//    }
//
//    private fun isTrackValid(params: TrackParams): Boolean {
//        // Implementation here
//        return true
//    }
//
//    private fun isRecordValid(params: RecordParams): Boolean {
//        // Implementation here
//        return true
//    }
//
//    private fun isAliasValid(params: AliasParams): Boolean {
//        // Implementation here
//        return true
//    }
//
//    private fun isConsentValid(params: ConsentParams): Boolean {
//        // Implementation here
//        return true
//    }
//
//    private fun getProfileId(): String {
//        // Implementation here
//        return ""
//    }
//
//    private fun getSessionId(): String {
//        // Implementation here
//        return ""
//    }
//
//    private fun getPageId(): String {
//        // Implementation here
//        return ""
//    }
//
//    private fun dispatchIntemptEvent(eventName: String, data: Map<String, Any>) {
//        // Implementation here
//    }
//
//    // Assuming necessary data classes are defined elsewhere

//
//// Define the data classes here (IdentifyModel, GroupModel, TrackModel, RecordModel, AliasModel, ConsentModel) as per your requirements.
