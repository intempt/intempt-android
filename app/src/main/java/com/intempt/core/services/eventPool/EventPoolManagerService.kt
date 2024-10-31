package com.intempt.core.services.eventPool
import com.intempt.core.autocapture.BaseComponent
import com.intempt.core.services.Logger
import com.intempt.core.eventModels.BaseIntemptEvent
import com.intempt.core.eventModels.IntemptEvent
import com.intempt.core.eventModels.TrackEvent
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.types.DispatchEventProps
import com.intempt.core.types.HandleEventTypeProps
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible

@Singleton
internal class EventPoolManagerService @Inject constructor(
    config: ConfigManagerService,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
): BaseComponent(){

    init {
        subscribe(Job()) { value ->
            Logger.log("IntemptCoreService | Received event of type: ${value.getEventType()}");
            handleIntemptEvent(value)
        }
    }


    private var eventReceiverJob: Job? = null
    private val _eventReceiver = MutableSharedFlow<IntemptEvent>(replay = 10)
    val eventReceiver: SharedFlow<IntemptEvent> = _eventReceiver;
    private val eventHandlers = EventHandlers(config);

    @Volatile
    var lastEvent: IntemptEvent? = null

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



        if(newEvent != null){
            //lastEvent = newEvent

            lastEvent = IntemptEvent(
                name = eventName,
                type = entityName,
                payload =  arrayOf(
                    newEvent
                )
            )
        }
    }

    fun emitEvent(event: IntemptEvent) {
        val isEmitted = _eventReceiver.tryEmit(event)
        Logger.log("EventPool | Event is emitted: $isEmitted")
        Logger.log("EventPool | $event")
    }

    fun subscribe(job:Job, callback: (value: IntemptEvent) -> Unit) {
        eventReceiverJob = CoroutineScope(dispatcher + job).launch {
            Logger.log("EventPoolManagerService | Started collecting events")
            eventReceiver.collect { value ->
                callback(value)
            }
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

    private fun handleIntemptEvent(event: IntemptEvent){
        Logger.log("handleIntemptEvent | $event")
        Logger.log("handleIntemptEvent type | ${event.getEventType()}")
        lastEvent = event

        Logger.log("handleIntemptEvent lastEvent | ${lastEvent?.getEventType()}")
    }

    private fun sendEvents(event: IntemptEvent) {}

    private fun validateEventCall() {}


    private val eventQueue: MutableList<IntemptEvent> = mutableListOf();

    private var lastDispatchTime: Long = System.currentTimeMillis();




}


