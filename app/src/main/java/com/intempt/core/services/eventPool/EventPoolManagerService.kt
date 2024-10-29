package com.intempt.core.services.eventPool
import com.intempt.core.autocapture.BaseComponent
import com.intempt.core.services.Logger
import com.intempt.core.eventModels.BaseIntemptEvent
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.types.DispatchEventProps
import com.intempt.core.types.HandleEventTypeProps
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible

@Singleton
internal class EventPoolManagerService @Inject constructor(
    config: ConfigManagerService,
): BaseComponent(){
    private val _eventReceiver = MutableSharedFlow<BaseIntemptEvent>(replay = 1)
    val eventReceiver: SharedFlow<BaseIntemptEvent> = _eventReceiver


    var lastEvent: BaseIntemptEvent? = null

    fun dispatchEvent(props: DispatchEventProps) {
        val (eventName,entityName, event, type, context, view) = props

        Logger.log("AutoCapture | Received Event: $eventName")
        Logger.log("AutoCapture | Received Type: $type")

        val newEvent = event
            ?: handleEventType(
                HandleEventTypeProps(
                    type = type,
                    entityName = entityName,
                    context = context,
                    view = view
                )
            )

        lastEvent = newEvent

        if(newEvent != null){
           val isEmitted = _eventReceiver.tryEmit(newEvent)
            Logger.log("AutoCapture | Event is emitted: $isEmitted")
            Logger.log("AutoCapture | $lastEvent")
        }
    }


    private fun handleEventType(props: HandleEventTypeProps): BaseIntemptEvent? {
        Logger.log("handleEventType | $props")
        try {
            val handler = eventHandlers::class.declaredFunctions.find { it.name == props.type }

            if(handler != null) {
                handler.isAccessible = true;

                Logger.log("AutoCapture | Successfully called function '${props.type}' on EventTypeHandler.")

                return handler.call(eventHandlers, props) as BaseIntemptEvent;
            }
            else{
                Logger.log("AutoCapture | Function '${props.type}' not found on EventTypeHandler.")
                return null
            }
        } catch (e: Exception) {
            Logger.log("AutoCapture | Error invoking function '${props.type}' on EventTypeHandler: ${e}")
            return null
        }
    }

    private fun sendEvents(event: BaseIntemptEvent) {}

    private fun validateEventCall() {}


    private val eventQueue: MutableList<BaseIntemptEvent> = mutableListOf();

    private var lastDispatchTime: Long = System.currentTimeMillis();

    private val eventHandlers = EventHandlers(config);


}


