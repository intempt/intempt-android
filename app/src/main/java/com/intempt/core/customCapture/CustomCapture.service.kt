package com.intempt.core.customCapture

import android.view.View
import com.intempt.core.R
import com.intempt.core.autocapture.BaseComponent
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.StorageManagerService
import com.intempt.core.types.Constants
import com.intempt.core.types.DispatchEventProps
import com.intempt.core.types.ScreenEventProps
import com.intempt.core.types.UiEventProps
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class CustomCaptureService @Inject constructor(
    private val storage: StorageManagerService,
    val logger: LoggerManagerService,
): BaseComponent(logger){
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


    fun setDoNotCaptureTag(view: View){
        view.setTag(R.id.intemptDoNotCapture, true)
    }

    fun logoutHandler(){
        storage.clearAllStorage()
    }




    fun isIdentifyValid(
        userId: String,
        eventTitle: String?,
        userAttributes: Map<String, String>?,
    ):Boolean{
        if(userId.isEmpty()){
            logger.error("Identify parameters are invalid: set 'userId' to use 'identify'.")
            return false
        }

        if(eventTitle == null && userAttributes != null){
            logger.error("Identify parameters are invalid: set 'eventTitle' to use 'userAttributes'.")
            return false
        }

        if(eventTitle != null && forbiddenEventNames.contains(eventTitle)){
            logger.error("The '$eventTitle' event title is forbidden")
            return false
        }

        return true
    }

    fun isGroupValid(
        accountId: String,
        eventTitle: String?,
        accountAttributes: Map<String, String>?,
    ):Boolean{
        if(accountId.isEmpty()){
            logger.error("Group parameters are invalid: 'accountId' is required.")
            return false
        }

        if(eventTitle != null && forbiddenEventNames.contains(eventTitle)){
            logger.error("The '$eventTitle' event title is forbidden")
            return false
        }

        if(eventTitle == null && accountAttributes != null){
            logger.error("Group parameters are invalid: set 'eventTitle' to use 'accountAttributes'.")
            return false
        }


        return true
    }

    fun isTrackValid(eventTitle: String?):Boolean{
        if(eventTitle.isNullOrEmpty()){
            logger.error("Track parameters are invalid: eventTitle is required.")
            return false
        }


        if(forbiddenEventNames.contains(eventTitle)){
            logger.error("The '$eventTitle' event title is forbidden")
            return false
        }
        return true
    }

    fun isConsentValid(action: String):Boolean{
        if(action.isNotEmpty() && action !== "accept" && action !== "reject"){
            logger.error("Consent parameters are invalid: action should be either 'reject' or 'accept'.")
            return false
        }
        return true
    }

    fun isProductListValid(products: List<Map<String, Any>>):Boolean{
        return products.all { product ->
            val productId = product["productId"]
            val quantity = product["quantity"]
            productId is String && productId.isNotBlank() && quantity is Int && quantity > 0
        }
    }

    fun onUiEventReceive(
        props: UiEventProps
    ): DispatchEventProps {
        logger.log("AutoCapture | Is UiEventProps")
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

    fun onScreenEventReceive(props:ScreenEventProps):DispatchEventProps{
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




