package com.intempt.core.services.eventPool

import android.app.Activity
import android.view.View
import com.intempt.core.eventModels.BaseIntemptEvent
import com.intempt.core.eventModels.FragmentTransitionEvent
import com.intempt.core.eventModels.ScreenViewEvent
import com.intempt.core.eventModels.UiElementEvent
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.types.HandleEventTypeProps
import com.intempt.core.types.ScreenViewProps


internal class EventHandlers(
    private val config: ConfigManagerService,
    private val logger: LoggerManagerService,
) {

    fun screen(props: HandleEventTypeProps): BaseIntemptEvent {
        logger.log("EventPool | Screen handler called")
        return ScreenViewEvent(
            ScreenViewProps(
                activity = props.context as Activity,
                entityName = props.entityName
            )
        );
    }

    fun fragment(props: HandleEventTypeProps): BaseIntemptEvent {
        logger.log("EventPool | Fragment called")
        val newEvent = FragmentTransitionEvent()
        logger.log("EventPool | Fragment Event: $newEvent")
        return newEvent
    }

    fun touch(props: HandleEventTypeProps): BaseIntemptEvent {
        logger.log("EventPool | Touch called")
        val view: View = props.view!!
        val newEvent = UiElementEvent(view, config, logger)

        logger.log("EventPool | Touch Event: $newEvent")
        return newEvent
    }

    fun change(props: HandleEventTypeProps): BaseIntemptEvent {
        try {
            logger.log("EventPool | Change called");
            val view: View = props.view!!
            val newEvent = UiElementEvent(view, config, logger);


            logger.log("EventPool | Change Event: $newEvent");
            return newEvent

        }catch (e: Exception) {
            logger.error("Error in change function: ${e.message}")
            throw e // Re-throw to ensure the exception is logged properly
        }
    }

//    fun installOrUpgrade(props: HandleEventTypeProps): BaseIntemptEvent {
//        logger.log("EventPool | InstallOrUpgrade called")
//        val newEvent = InstallOrUpgradeEvent(props.context)
//
//        logger.log("EventPool | App installation/upgrade Event: $newEvent")
//        return newEvent
//    }
}