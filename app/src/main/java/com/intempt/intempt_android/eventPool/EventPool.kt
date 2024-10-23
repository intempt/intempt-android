package com.intempt.intempt_android.eventPool
import com.intempt.intempt_android.Logger
import com.intempt.intempt_android.eventModels.BaseIntemptEvent
import com.intempt.intempt_android.autocapture.sessiontracker.SessionTracker
import com.intempt.intempt_android.types.DispatchEventProps
import com.intempt.intempt_android.types.HandleEventTypeProps
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
        val handler = eventHandlers::class.declaredFunctions.find { it.name == props.type }

        if(handler != null) {
           handler.isAccessible = true;
            try {
                val newEvent = handler.call(eventHandlers, props);
                Logger.log("AutoCapture | Successfully called function '${props.type}' on EventTypeHandler.")
            } catch (e: Exception) {
                Logger.log("AutoCapture | Error invoking function '${props.type}' on EventTypeHandler: ${e.message}")
            }
        }
    }

    private fun sendEvents(event: BaseIntemptEvent) {}

    private fun validateEventCall() {}


    private val eventQueue: MutableList<BaseIntemptEvent> = mutableListOf();

    private var lastDispatchTime: Long = System.currentTimeMillis();

    private val eventHandlers = EventHandlers();

}


