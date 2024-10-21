package com.intempt.intempt_android.eventPool

import android.app.Activity
import android.view.View
import com.intempt.intempt_android.Logger
import com.intempt.intempt_android.autocapture.eventModels.BaseIntemptEvent
import com.intempt.intempt_android.autocapture.eventModels.FragmentTransitionEvent
import com.intempt.intempt_android.autocapture.eventModels.InstallOrUpgradeEvent
import com.intempt.intempt_android.autocapture.eventModels.ScreenViewEvent
import com.intempt.intempt_android.autocapture.eventModels.UiElementEvent
import com.intempt.intempt_android.types.HandleEventTypeProps
import com.intempt.intempt_android.types.ScreenViewProps


internal class EventHandlers {

    internal fun screen(props: HandleEventTypeProps): BaseIntemptEvent {
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

    internal fun fragment(props: HandleEventTypeProps): BaseIntemptEvent {
        Logger.log("EventPool | Fragment called")
        val newEvent = FragmentTransitionEvent()
        Logger.log("EventPool | Fragment Event: $newEvent")
        return newEvent
    }

    internal fun touch(props: HandleEventTypeProps): BaseIntemptEvent {
        Logger.log("EventPool | Touch called")
        val view: View = props.view!!
        val newEvent = UiElementEvent(view)
        Logger.log("EventPool | Touch Event: $newEvent")
        return newEvent
    }

    internal fun change(props: HandleEventTypeProps): BaseIntemptEvent {
        Logger.log("EventPool | Change called");
        val view: View = props.view!!
        val newEvent = UiElementEvent(view);

        Logger.log("EventPool | Change Event: $newEvent");
        return newEvent
    }

    internal fun installOrUpgrade(props: HandleEventTypeProps): BaseIntemptEvent {
        Logger.log("EventPool | InstallOrUpgrade called")
        val newEvent = InstallOrUpgradeEvent(props.context)

        Logger.log("EventPool | App installation/upgrade Event: $newEvent")
        return newEvent
    }
}