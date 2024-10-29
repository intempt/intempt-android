package com.intempt.core.services.eventPool

import android.app.Activity
import android.view.View
import com.intempt.core.services.Logger
import com.intempt.core.eventModels.BaseIntemptEvent
import com.intempt.core.eventModels.FragmentTransitionEvent
import com.intempt.core.eventModels.InstallOrUpgradeEvent
import com.intempt.core.eventModels.ScreenViewEvent
import com.intempt.core.eventModels.UiElementEvent
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.types.HandleEventTypeProps
import com.intempt.core.types.ScreenViewProps


internal class EventHandlers(
    private val config: ConfigManagerService
) {

    fun screen(props: HandleEventTypeProps): BaseIntemptEvent {
        Logger.log("EventPool | Screen called")
        val newEvent = ScreenViewEvent(
            ScreenViewProps(
                activity = props.context as Activity,
                entityName = props.entityName
            )
        );
        Logger.log("EventPool | Screen Event: $newEvent")
        return newEvent
    }

    fun fragment(props: HandleEventTypeProps): BaseIntemptEvent {
        Logger.log("EventPool | Fragment called")
        val newEvent = FragmentTransitionEvent()
        Logger.log("EventPool | Fragment Event: $newEvent")
        return newEvent
    }

    fun touch(props: HandleEventTypeProps): BaseIntemptEvent {
        Logger.log("EventPool | Touch called")
        val view: View = props.view!!
        val newEvent = UiElementEvent(view, config)

        Logger.log("EventPool | Touch Event: $newEvent")
        return newEvent
    }

    fun change(props: HandleEventTypeProps): BaseIntemptEvent {
        try {
            Logger.log("EventPool | Change called");
            val view: View = props.view!!
            val newEvent = UiElementEvent(view, config);


            Logger.log("EventPool | Change Event: $newEvent");
            return newEvent

        }catch (e: Exception) {
            Logger.error("Error in change function: ${e.message}")
            throw e // Re-throw to ensure the exception is logged properly
        }
    }

    fun installOrUpgrade(props: HandleEventTypeProps): BaseIntemptEvent {
        Logger.log("EventPool | InstallOrUpgrade called")
        val newEvent = InstallOrUpgradeEvent(props.context)

        Logger.log("EventPool | App installation/upgrade Event: $newEvent")
        return newEvent
    }
}