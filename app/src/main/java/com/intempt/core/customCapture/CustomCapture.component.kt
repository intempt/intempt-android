package com.intempt.core.customCapture

import com.intempt.core.eventModels.IntemptEvent
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.IntemptEventManagerService
import com.intempt.core.services.eventPool.EventPoolManagerService
import com.intempt.core.types.AutoCaptureParam
import com.intempt.core.types.DispatchEventProps
import com.intempt.core.types.EventType
import com.intempt.core.types.ScreenEventProps
import com.intempt.core.types.UiEventProps

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class CustomCaptureComponent @Inject constructor(
    private val srv: CustomCaptureService,
    private val config: ConfigManagerService,
    private val eventPool: EventPoolManagerService,
    private val intemptEvent: IntemptEventManagerService
) {

    fun captureUi(listenerType: String, param: AutoCaptureParam){
        if (!config.isUserOptIn) return
        srv.logger.log("autoCapture | Is $listenerType listener")

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
        srv.logger.log("Invoke enableLogging")
        config.isLoggingEnabled = true
    }

    fun disableLogging(){
        srv.logger.log("Invoke disableLogging")
        config.isLoggingEnabled = false
    }

    fun optIn(){
        srv.logger.log("Invoke optIn")
        config.isUserOptIn = true
    }

    fun optOut(){
        srv.logger.log("Invoke optOut")
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

        srv.logger.log("Invoke identify")

         val newEvent = IntemptEvent(
             name = eventTitle ?: "Identify",
             type = EventType.Identify.value,
             payload = intemptEvent.generateIdentifyEventPayload(
                 userId,
                 userAttributes,
                 data
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

        srv.logger.log("Invoke group")

        val newEvent = IntemptEvent(
            name = eventTitle ?: "Identify",
            type = EventType.Group.value,
            payload = intemptEvent.generateGroupEventPayload(
                accountId,
                accountAttributes
            )
        )

        eventPool.emitEvent(newEvent)
    }

    fun track(eventTitle: String,data: Map<String, String>){
        if (!config.isUserOptIn) return
        if (!srv.isTrackValid(eventTitle)) return

        srv.logger.log("Invoke track")

        val newEvent = IntemptEvent(
            name = eventTitle,
            type = EventType.Track.value,
            payload = intemptEvent.generateTrackEventPayload(data)
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

        srv.logger.log("Invoke record")

        val newEvent = IntemptEvent(
            name = eventTitle,
            type = EventType.Record.value,
            payload = intemptEvent.generateRecordEventPayload(
                accountId,
                userId,
                accountAttributes,
                userAttributes,
                data
            )
        )

        eventPool.emitEvent(newEvent)
    }

    fun alias(userId: String, anotherUserId: String){
        if (!config.isUserOptIn) return

        srv.logger.log("Invoke alias")

        val newEvent = IntemptEvent(
            name = "Identify",
            type = EventType.Alias.value,
            payload = intemptEvent.generateAliasEventPayload(userId, anotherUserId)
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

        srv.logger.log("Invoke consent")

        val newEvent = IntemptEvent(
            name = "Consent",
            type = EventType.Consent.value,
            payload = intemptEvent.generateConsentEventPayload(
                action,
                email,
                message,
                category,
                validUntil,
                config.sourceId
            )
        )

        eventPool.emitEvent(newEvent)
    }

    fun logOut(){
        if (!config.isUserOptIn) return
        srv.logger.log("Invoke logOut")
        srv.logoutHandler()
    }




}