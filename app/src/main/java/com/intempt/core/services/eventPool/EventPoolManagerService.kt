package com.intempt.core.services.eventPool
import android.content.Context
import com.intempt.core.autocapture.BaseComponent
import com.intempt.core.eventModels.BaseIntemptEvent
import com.intempt.core.eventModels.IntemptEvent
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.HttpManagerService
import com.intempt.core.services.IntemptEventManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.StorageManagerService
import com.intempt.core.types.Constants
import com.intempt.core.types.DispatchEventProps
import com.intempt.core.types.HandleEventTypeProps
import com.intempt.core.types.IntemptEventProvider
import io.ktor.client.statement.HttpResponse
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
internal class EventPoolManagerService @Inject constructor(
    private val context: Context,
    private val config: ConfigManagerService,
    private val logger: LoggerManagerService,
    private val http: HttpManagerService,
    private val storage: StorageManagerService,
    private val intemptEvent: IntemptEventManagerService,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
): BaseComponent(logger){

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val eventQueue: MutableList<IntemptEvent> = mutableListOf();

    private var lastDispatchTime: Long = System.currentTimeMillis();

    private val eventHandlers = EventHandlers(logger, intemptEvent);
    private var eventReceiverJob: Job? = null
    private val _eventReceiver = MutableSharedFlow<IntemptEvent>(replay = 10)

    val eventReceiver: SharedFlow<IntemptEvent> = _eventReceiver;

    @Volatile
    var lastEvent: IntemptEvent? = null



    init {
        subscribe(Job()) { value ->
            logger.log("IntemptCoreService | Received event of type: ${value.getEventType()}");
            handleIntemptEvent(value)
        }
    }

    fun dispatchEvent(props: DispatchEventProps) {
        val (eventName,entityName, event, type, context, view) = props

        logger.log("AutoCapture | Received Event: $eventName")
        logger.log("AutoCapture | Received Type: $type")

        val payload = event
            ?: handleEventType(
                HandleEventTypeProps(
                    type = type,
                    entityName = entityName,
                    context = context,
                    view = view
                )
            )



        if(payload != null){
            lastEvent = IntemptEvent(
                name = eventName,
                type = entityName,
                payload = payload
            )
        }
    }

    fun emitEvent(event: IntemptEvent) {
        val isEmitted = _eventReceiver.tryEmit(event)
        if(isEmitted){
            lastEvent = event
        }

        logger.log("EventPool | Event is emitted: $isEmitted")
        logger.log("EventPool | $event")
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

                logger.log("AutoCapture | Successfully called function '${props.type}' on EventTypeHandler.")

                return handler.call(eventHandlers, props) as Array<IntemptEventProvider>;
            }
            else{
                logger.log("AutoCapture | Function '${props.type}' not found on EventTypeHandler.")
                return null
            }
        } catch (e: Exception) {
            logger.error("AutoCapture | Error invoking function '${props.type}' on EventTypeHandler: ${e.message}")
            return null
        }
    }

    private fun handleIntemptEvent(event: IntemptEvent){
        logger.log("handleIntemptEvent | Received event: $event")
        eventQueue.add(event)
        logger.log("EventPoolManagerService | eventQueue size: ${eventQueue.size}")
        validateEventCall {
            sendEvents()
        }
    }

    private fun sendEvents() {
        if(eventQueue.isEmpty()) return

        val requestBodyJson = generateRequestBody()

        coroutineScope.launch {
                try {
                    val response: HttpResponse = http.post(config.eventsUrl, requestBodyJson)
                    handleResponse(response)
                }
                catch (e: Exception) {
                    logger.error("sendEvents | Exception occurred while sending events: ${e.message}")
                }
        }
    }

    private fun generateRequestBody(): JSONObject {
        val trackArray = JSONArray()
        for (event in eventQueue) {
            val eventJsonObject = JSONObject().apply {
                put("name", event.name)
                put("payload", JSONArray(event.payload))
            }
            trackArray.put(eventJsonObject)
        }
        return JSONObject().apply {
            put("track", trackArray)
        }
    }

    private fun handleResponse(response: HttpResponse){
        if (response.status.value == Constants.SUCCES_CODE) {

            logger.log("Successfully sent events to server")
            eventQueue.clear()
            lastDispatchTime = System.currentTimeMillis()
        } else {
            logger.error("Failed to send events: ${response.status}")
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


