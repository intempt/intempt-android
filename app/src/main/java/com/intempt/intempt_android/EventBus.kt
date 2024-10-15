package com.intempt.intempt_android
import android.app.Activity
import com.intempt.intempt_android.autocapture.screenTracker.FragmentTransitionEvent
import com.intempt.intempt_android.autocapture.screenTracker.ScreenViewEvent
import com.intempt.intempt_android.autocapture.sessiontracker.SessionTracker
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible



object EventTypeHandler {
    fun screen(props: HandleEventTypeProps) {
        Logger.log("Screen called")
        val newEvent = ScreenViewEvent(
           ScreenViewProps(
               activity = props.context as Activity,
               entityName = props.entityName
           )
        );
        Logger.log("Screen Event: $newEvent")
    }

    fun fragment(props:HandleEventTypeProps) {
        Logger.log("Fragment called")
        val newEvent = FragmentTransitionEvent(props.context as Activity)
        Logger.log("Fragment Event: $newEvent")
    }

    fun touch(props:HandleEventTypeProps){}
}


object EventBus {
    fun dispatchEvent(props:DispatchEventProps) {
        val (eventName,entityName, event, type, context) = props

        Logger.log("Received Event: $eventName")
        Logger.log("Received Type: $type")

        SessionTracker.handleSession(event, context)

        handleEventType(
            HandleEventTypeProps(
            type = type,
            entityName = entityName,
            context = context
          )
        )

    }

    private fun handleEventType(props:HandleEventTypeProps){
        val function = EventTypeHandler::class.declaredFunctions.find { it.name == props.type }

        if(function != null) {
            function.isAccessible = true;
            try {
                function.call(EventTypeHandler,props)
                Logger.log("Successfully called function '${props.type}' on EventTypeHandler.")
            } catch (e: Exception) {
                Logger.log("Error invoking function '${props.type}' on EventTypeHandler: ${e.message}")
            }
        }
    }
}