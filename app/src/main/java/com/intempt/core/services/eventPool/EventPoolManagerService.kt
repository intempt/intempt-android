package com.intempt.core.services.eventPool
import com.intempt.core.autocapture.BaseComponent
import com.intempt.core.eventModels.IntemptEvent
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.HttpManagerService
import com.intempt.core.services.IntemptEventManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.types.DispatchEventProps
import com.intempt.core.types.EventType
import com.intempt.core.types.HandleEventTypeProps
import com.intempt.core.types.IntemptEventProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible

@Singleton
internal open class EventPoolManagerService @Inject constructor(
    private val config: ConfigManagerService,
    private val logger: LoggerManagerService,
    private val http: HttpManagerService,
    private val intemptEvent: IntemptEventManagerService,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
): BaseComponent(logger){

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val eventQueue: MutableList<IntemptEvent> = mutableListOf();

    private var lastDispatchTime: Long = System.currentTimeMillis();

    private val eventHandlers = EventHandlers(logger, intemptEvent);
    private var eventReceiverJob: Job? = null
    private val _eventReceiver = MutableSharedFlow<IntemptEvent>(replay = 10)

    val eventReceiver: SharedFlow<IntemptEvent> = _eventReceiver;

    val eventsList: List<IntemptEvent>
        get() = eventQueue.toList()

    init {
        subscribe(Job()) { value ->
            logger.log("IntemptCoreService | Received event of type: ${value.getEventType()}");
            handleIntemptEvent(value)
        }
    }



    @Synchronized
    fun addEvent(event: IntemptEvent) {
        eventQueue.add(event)
    }

    fun dispatchEvent(props: DispatchEventProps) {
        val (eventName,entityName, event, type, context, view) = props
        logger.log("AutoCapture | Received Event: $eventName; Type:$type")

         val payload = event ?: handleEventType(
            HandleEventTypeProps(
                type = type,
                entityName = entityName,
                context = context,
                view = view
            )
         )
        if(!payload.isNullOrEmpty()){
            logger.log("AutoCapture | Successfully called function '${props.type}' on EventTypeHandler.")
            emitEvent(
                IntemptEvent(
                    name = eventName,
                    type = entityName,
                    payload = payload
                )
            )
        }
    }

    fun emitEvent(event: IntemptEvent):Boolean {
        val isEmitted = _eventReceiver.tryEmit(event)
        logger.log("EventPool | Event is emitted: $isEmitted")
        return isEmitted
    }

    fun subscribe(job:Job, callback: (value: IntemptEvent) -> Unit) {
        try {
            eventReceiverJob = CoroutineScope(dispatcher + job).launch {
                logger.log("EventPoolManagerService | Started collecting events")
                eventReceiver.collect { value ->
                    callback(value)
                }
            }
        }
        catch (e: Exception) {
            logger.log("Error during collection: ${e.message}")
        }
    }

    private fun handleEventType(props: HandleEventTypeProps): Array<IntemptEventProvider>? {
        logger.log("handleEventType | $props")
        try {
            val handler = eventHandlers::class.declaredFunctions.find { it.name == props.type }

            if(handler != null) {
                handler.isAccessible = true;

                return handler.call(eventHandlers, props) as Array<IntemptEventProvider>;
            }
            else{

                logger.log("AutoCapture | Function '${props.type}' not found on EventTypeHandler.")
                return null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            logger.error("AutoCapture | Error invoking function '${props.type}' on EventTypeHandler: ${e.message}")
            return null
        }
    }

    private fun handleIntemptEvent(event: IntemptEvent){
        val eventType = event.getEventType()

        when(eventType){
            EventType.Consent.value -> sendConsentEvent(event)
            else -> {
                addEvent(event)
                validateEventCall {
                    sendTrackEvents()
                }
            }
        }

        logger.log("EventPoolManagerService | eventQueue size: ${eventQueue.size}")

    }

    private fun sendConsentEvent(event: IntemptEvent){
        val requestBodyJson = JSONObject(event.payload.first().toFormated())

        coroutineScope.launch {
            try {
                http.post(config.consentUrl, requestBodyJson)
                logger.log("Successfully sent events to server")
                lastDispatchTime = System.currentTimeMillis()
            }
            catch (e: Exception) {
                logger.error("sendConsentEvent | Exception occurred while sending events: ${e.message}")
            }
        }
    }

    private fun sendTrackEvents() {
        if(eventQueue.isEmpty()) return

        val requestBodyJson = generateTrackRequestBody()
        eventQueue.clear()
        logger.log("sendEvents | Request body: $requestBodyJson")

        coroutineScope.launch {
                try {
                    http.post(config.eventsUrl, requestBodyJson)
                    logger.log("Successfully sent events to server")
                    lastDispatchTime = System.currentTimeMillis()
                }
                catch (e: Exception) {
                    logger.error("sendEvents | Exception occurred while sending events: ${e.message}")
                }
        }
    }

    private fun generateTrackRequestBody(): JSONObject {
        val trackArray = JSONArray()

        for (event in eventQueue) {
            val payloadArray = JSONArray()

            for (payloadItem in event.payload) {
                val payloadJsonObject = JSONObject(payloadItem.toFormated())

                payloadArray.put(payloadJsonObject)
            }

            val eventJsonObject = JSONObject(event.toFormated())
            trackArray.put(eventJsonObject)
        }
        return JSONObject().apply {
            put("track", trackArray)
        }
    }

    private fun validateEventCall( callback: () -> Unit) {
        if(config.isQueueEnabled){
            if (eventQueue.size >= config.itemsInQueue || System.currentTimeMillis() - lastDispatchTime >= config.timeBuffer) {
                callback()
            }
        }
        else {
            callback()
        }
    }

}


