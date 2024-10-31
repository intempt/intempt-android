package com.intempt.core.customCapture

import com.intempt.core.eventModels.GroupEvent
import com.intempt.core.eventModels.IdentifyEvent
import com.intempt.core.eventModels.IntemptEvent
import com.intempt.core.eventModels.RecordEvent
import com.intempt.core.eventModels.TrackEvent
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.Logger
import com.intempt.core.services.eventPool.EventPoolManagerService
import javax.inject.Inject

internal class CustomCaptureComponent @Inject constructor(
    private val srv: CustomCaptureService,
    private val config: ConfigManagerService,
    private val eventPool: EventPoolManagerService
) {

    fun identify(
         userId: String,
         eventTitle: String? = null,
         userAttributes: Map<String, String>? = null,
         data: Map<String, String>? = null,
    ) {
        if (!config.isUserOptIn) return
        if (!srv.isIdentifyValid(userId,eventTitle,userAttributes)) return

         Logger.log("Invoke identify")

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

        Logger.log("Invoke group")

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

        Logger.log("Invoke track")

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

        Logger.log("Invoke record")

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


}