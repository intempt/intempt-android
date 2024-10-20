package com.intempt.intempt_android
import android.app.Activity
import android.view.View
import com.intempt.intempt_android.autocapture.eventModels.FragmentTransitionEvent
import com.intempt.intempt_android.autocapture.eventModels.InstallOrUpgradeEvent
import com.intempt.intempt_android.autocapture.eventModels.ScreenViewEvent
import com.intempt.intempt_android.autocapture.eventModels.UiElementEvent
import com.intempt.intempt_android.autocapture.sessiontracker.SessionTracker
import com.intempt.intempt_android.types.DispatchEventProps
import com.intempt.intempt_android.types.HandleEventTypeProps
import com.intempt.intempt_android.types.ScreenViewProps
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible


class EventPool {
    fun dispatchEvent(props: DispatchEventProps) {
        val (eventName,entityName, event, type, context, view) = props

        Logger.log("AutoCapture | Received Event: $eventName")
        Logger.log("AutoCapture | Received Type: $type")

        SessionTracker.handleSession(event, context)

        handleEventType(
            HandleEventTypeProps(
                type = type,
                entityName = entityName,
                context = context,
                view = view
            )
        )

    }

    private fun handleEventType(props: HandleEventTypeProps){
        val function = this::class.declaredFunctions.find { it.name == props.type }

        if(function != null) {
            function.isAccessible = true;
            try {
                function.call(this, props)
                Logger.log("AutoCapture | Successfully called function '${props.type}' on EventTypeHandler.")
            } catch (e: Exception) {
                Logger.log("AutoCapture | Error invoking function '${props.type}' on EventTypeHandler: ${e.message}")
            }
        }
    }

    private fun screen(props: HandleEventTypeProps) {
        Logger.log("EventPool | Screen called")
        val newEvent = ScreenViewEvent(
            ScreenViewProps(
                activity = props.context as Activity,
                entityName = props.entityName
            )
        );
        Logger.log("EventPool | Screen Event: $newEvent")
    }

    private fun fragment(props: HandleEventTypeProps) {
        Logger.log("EventPool | Fragment called")
        val newEvent = FragmentTransitionEvent()
        Logger.log("EventPool | Fragment Event: $newEvent")
    }

    private fun touch(props: HandleEventTypeProps){
        Logger.log("EventPool | Touch called")
        val view: View = props.view!!
        val newEvent = UiElementEvent(view)
        Logger.log("EventPool | Touch Event: $newEvent")
    }

    private fun installOrUpgrade(props: HandleEventTypeProps){
        Logger.log("EventPool | InstallOrUpgrade called")
        val newEvent = InstallOrUpgradeEvent(props.context)

        Logger.log("EventPool | App installation/upgrade Event: $newEvent")
    }
}