package com.intempt.core.services.eventPool

import android.app.Activity
import android.view.View
import com.intempt.core.services.IntemptEventManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.types.HandleEventTypeProps
import com.intempt.core.types.IntemptEventProvider



internal class EventHandlers(
    private val logger: LoggerManagerService,
    private val intemptEvent: IntemptEventManagerService
) {

    fun fragment(props: HandleEventTypeProps):Array<IntemptEventProvider> {
        logger.log("EventPool | Fragment called")
        val newEvent = intemptEvent.generateFragmentTransitionEventPayload()
        logger.log("EventPool | Fragment Event: $newEvent")
        return newEvent
    }

    fun screen(props: HandleEventTypeProps):Array<IntemptEventProvider> {
        logger.log("EventPool | Screen handler called")

        val newEvent = intemptEvent.generateScreenViewEventPayload(
            props.context as Activity,
            props.entityName
        )
        logger.log("EventPool | Screen Event: $newEvent")
        return newEvent
    }

    fun touch(props: HandleEventTypeProps):Array<IntemptEventProvider> {
        logger.log("EventPool | Touch called")
        val view: View = props.view!!
        val newEvent = intemptEvent.generateUiElementEventPayload(
            view
        )

        logger.log("EventPool | Touch Event: $newEvent")
        return newEvent
    }

    fun change(props: HandleEventTypeProps):Array<IntemptEventProvider> {
            logger.log("EventPool | Change called");
            val view: View = props.view!!
            val newEvent = intemptEvent.generateUiElementEventPayload(
                view
            )
            logger.log("EventPool | Change Event: $newEvent");
            return newEvent
    }

    fun installOrUpgrade(props: HandleEventTypeProps): Array<IntemptEventProvider> {
        logger.log("EventPool | InstallOrUpgrade called")
        val newEvent = intemptEvent.generateInstallUpgradeEventPayload()

        logger.log("EventPool | App installation/upgrade Event: $newEvent")
        return newEvent
    }
}