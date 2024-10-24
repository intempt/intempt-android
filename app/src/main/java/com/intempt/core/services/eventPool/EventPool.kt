package com.intempt.core.services.eventPool
import com.intempt.core.autocapture.BaseComponent
import com.intempt.core.services.Logger
import com.intempt.core.eventModels.BaseIntemptEvent
import com.intempt.core.autocapture.sessiontracker.SessionTrackerService
import com.intempt.core.types.DispatchEventProps
import com.intempt.core.types.HandleEventTypeProps
import javax.inject.Inject
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible


internal class EventPool @Inject constructor(
    private val sessionSrv: SessionTrackerService
): BaseComponent(){
    fun dispatchEvent(props: DispatchEventProps) {
        val (eventName,entityName, event, type, context, view) = props

        Logger.log("AutoCapture | Received Event: $eventName")
        Logger.log("AutoCapture | Received Type: $type")

        //sessionSrv.handleSession(event)

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
                Logger.log("AutoCapture | $newEvent")
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


