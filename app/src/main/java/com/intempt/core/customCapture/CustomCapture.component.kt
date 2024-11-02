package com.intempt.core.customCapture

import com.intempt.core.eventModels.AliasEvent
import com.intempt.core.eventModels.ConsentEvent
import com.intempt.core.eventModels.GroupEvent
import com.intempt.core.eventModels.IdentifyEvent
import com.intempt.core.eventModels.IntemptEvent
import com.intempt.core.eventModels.RecordEvent
import com.intempt.core.eventModels.TrackEvent
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.eventPool.EventPoolManagerService
import com.intempt.core.types.AutoCaptureParam
import com.intempt.core.types.DispatchEventProps
import com.intempt.core.types.ScreenEventProps
import com.intempt.core.types.UiEventProps

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class CustomCaptureComponent @Inject constructor(
    private val srv: CustomCaptureService,
    private val config: ConfigManagerService,
    private val eventPool: EventPoolManagerService,
    private val logger: LoggerManagerService
) {

    fun autoCapture(listenerType: String, param: AutoCaptureParam){
        logger.log("autoCapture | Is $listenerType listener")

        val payload: DispatchEventProps = when (param) {
            is UiEventProps -> srv.onUiEventReceive(param)
            is ScreenEventProps -> srv.onScreenEventReceive(param)
        }

         eventPool.dispatchEvent(payload)
    }



    fun isLoggingEnabled(): Boolean {
        return config.isLoggingEnabled
    }

    fun enableLogging(){
        logger.log("Invoke enableLogging")
        config.isLoggingEnabled = true
    }

    fun disableLogging(){
        logger.log("Invoke disableLogging")
        config.isLoggingEnabled = false
    }

    fun optIn(){
        logger.log("Invoke optIn")
        config.isUserOptIn = true
    }

    fun optOut(){
        logger.log("Invoke optOut")
        config.isUserOptIn = false
    }


    fun identify(
         userId: String,
         eventTitle: String? = null,
         userAttributes: Map<String, String>? = null,
         data: Map<String, String>? = null,
    ) {
        if (!config.isUserOptIn) return
        if (!srv.isIdentifyValid(userId,eventTitle,userAttributes)) return

        logger.log("Invoke identify")

         val newEvent = IntemptEvent(
             name = eventTitle ?: "Identify",
             type = "identify",
             payload =  arrayOf(
                 IdentifyEvent(
                     userId,
                     userAttributes,
                     data
                 )
             )
         )

         eventPool.emitEvent(newEvent)
    }

    fun group(
        accountId: String,
        eventTitle: String? = null,
        accountAttributes: Map<String, String>? = null
    ) {
        if (!config.isUserOptIn) return
        if (!srv.isGroupValid(accountId,eventTitle,accountAttributes)) return

        logger.log("Invoke group")

        val newEvent = IntemptEvent(
            name = eventTitle ?: "Identify",
            type = "group",
            payload =  arrayOf(
                GroupEvent(
                    accountId,
                    accountAttributes,
                )
            )
        )

        eventPool.emitEvent(newEvent)
    }

    fun track(eventTitle: String,data: Map<String, String>){
        if (!config.isUserOptIn) return
        if (!srv.isTrackValid(eventTitle)) return

        logger.log("Invoke track")

        val newEvent = IntemptEvent(
            name = eventTitle,
            type = "track",
            payload =  arrayOf(
                TrackEvent(
                    data
                )
            )
        )

        eventPool.emitEvent(newEvent)
    }

    fun record(
        eventTitle: String,
        accountId: String? = null,
        userId: String? = null,
        accountAttributes: Map<String, String>? = null,
        userAttributes: Map<String, String>? = null,
        data: Map<String, String>? = null,
    ){
        if (!config.isUserOptIn) return
        if (!srv.isTrackValid(eventTitle)) return

        logger.log("Invoke record")

        val newEvent = IntemptEvent(
            name = eventTitle,
            type = "record",
            payload =  arrayOf(
                RecordEvent(
                    accountId, userId,accountAttributes, userAttributes, data
                )
            )
        )

        eventPool.emitEvent(newEvent)
    }

    fun alias(userId: String, anotherUserId: String, ){
        if (!config.isUserOptIn) return

        logger.log("Invoke alias")

        val newEvent = IntemptEvent(
            name = "Identify",
            type = "alias",
            payload =  arrayOf(
                AliasEvent(userId, anotherUserId)
            )
        )

        eventPool.emitEvent(newEvent)
    }

    fun consent(
        action: String,
        validUntil: Long,
        email: String ? = null,
        message: String ? = null,
        category: String ? = null,
        ){
        if (!config.isUserOptIn) return
        if (!srv.isConsentValid(action)) return

        logger.log("Invoke alias")

        val newEvent = IntemptEvent(
            name = "Identify",
            type = "consent",
            payload =  arrayOf(
                ConsentEvent(
                    action,
                    email,
                    message,
                    category,
                    sourceId = config.sourceId,
                    validUntil,
                )
            )
        )

        eventPool.emitEvent(newEvent)
    }

    fun logOut(){
        if (!config.isUserOptIn) return

        val newEvent = IntemptEvent(
            name = "Log out",
            type = "logOut",
            payload =  arrayOf()
        )

        eventPool.emitEvent(newEvent)

    }

}