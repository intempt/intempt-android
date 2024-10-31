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
internal class CustomCaptureService: BaseComponent(){
    internal fun autoCapture(listenerType: String, param: AutoCaptureParam){
        Logger.log("autoCapture | Is $listenerType listener")

        val payload: DispatchEventProps = when (param) {
            is UiEventProps -> onUiEventReceive(param)
            is ScreenEventProps -> onScreenEventReceive(param)
        }

       // eventPool.dispatchEvent(payload)
    }




    internal fun isIdentifyValid(
        userId: String,
        eventTitle: String?,
        userAttributes: Map<String, String>?,
    ):Boolean{

        if(userId.isEmpty()){
            Logger.error("Identify parameters are invalid: set 'userId' to use 'identify'.")
            return false
        }

        if(eventTitle == null && userAttributes != null){
            Logger.error("Identify parameters are invalid: set 'eventTitle' to use 'userAttributes'.")
            return false
        }

        if(eventTitle != null && forbiddenEventNames.contains(eventTitle)){
            Logger.error("The '$eventTitle' event title is forbidden")
            return false
        }

        return true
    }

    internal fun isGroupValid(
        accountId: String,
        eventTitle: String?,
        accountAttributes: Map<String, String>?,
    ):Boolean{
        if(accountId.isEmpty()){
            Logger.error("Group parameters are invalid: 'accountId' is required.")
            return false
        }

        if(eventTitle != null && forbiddenEventNames.contains(eventTitle)){
            Logger.error("The '$eventTitle' event title is forbidden")
            return false
        }

        if(eventTitle == null && accountAttributes != null){
            Logger.error("Group parameters are invalid: set 'eventTitle' to use 'accountAttributes'.")
            return false
        }


        return true
    }

    internal fun isTrackValid(eventTitle: String?):Boolean{
        if(eventTitle.isNullOrEmpty()){
            Logger.error("Track parameters are invalid: eventTitle is required.")
            return false
        }


        if(forbiddenEventNames.contains(eventTitle)){
            Logger.error("The '$eventTitle' event title is forbidden")
            return false
        }
        return true
    }

    private val forbiddenEventNames: Array<String> = arrayOf(
        "auto-track",
        "view page",
        "leave page",
        "change on",
        "click on",
        "submit on",
        "identify",
        "consent"
    )


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
