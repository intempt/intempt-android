package com.intempt.core.services.eventPool
import com.intempt.core.autocapture.BaseComponent
import com.intempt.core.eventModels.IntemptEvent
import com.intempt.core.queue.ConsentAuditLog
import com.intempt.core.queue.DeliveryMessages
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.HttpManagerService
import com.intempt.core.services.IntemptEventManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.types.DispatchEventProps
import com.intempt.core.types.EventType
import com.intempt.core.types.HandleEventTypeProps
import com.intempt.core.types.IntemptEventProvider
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible

@Singleton
internal open class EventPoolManagerService
    @Inject
    constructor(
        private val config: ConfigManagerService,
        private val logger: LoggerManagerService,
        private val http: HttpManagerService,
        private val intemptEvent: IntemptEventManagerService,
        private val delivery: DeliveryMessages,
        private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
        // Nullable with a null default so every existing call site (production wiring in
        // IntemptCoreModule aside) keeps compiling unchanged. Production wiring supplies a
        // real instance; null only shows up in tests that don't care about the audit trail.
        private val consentAudit: ConsentAuditLog? = null,
    ) : BaseComponent(logger) {
        // `dispatcher`, not Dispatchers.IO. This class accepted an injected dispatcher and
        // then ignored it here, so a test that supplied one still had the collector running
        // on the real IO pool — the injection was decorative. That is why work kept
        // outliving tests and kotlinx.coroutines.test kept blaming whichever test ran next.
        private val coroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

        // Retained: sendConsentEvent still stamps this. Consent posts immediately and has
        // never been queued, so it does not move to the durable queue.
        private var lastDispatchTime: Long = System.currentTimeMillis()

        // The dispatcher has to be handed down. EventHandlers defaults to Dispatchers.IO,
        // so omitting it here meant every coroutine EventHandlers launched ran on the real
        // IO pool no matter what a test injected into this class.
        private val eventHandlers = EventHandlers(logger, intemptEvent, dispatcher)
        private var eventReceiverJob: Job? = null

        // extraBufferCapacity, not replay alone.
        //
        // With `replay = 10` and no extra buffer, `tryEmit` returns false — and DROPS the event —
        // as soon as a subscriber has not drained the 10 slots. There are two subscribers, and a
        // burst of 45 events is a normal thing for a host app to do, so the buffer filled in
        // practice. The comment further down already warned that a full buffer means "silent drops
        // upstream of the durable queue, reintroducing the exact loss this work exists to
        // eliminate"; nothing had ever measured whether it filled.
        //
        // Caught on a real device by the first assertion that ever read a capture method's return
        // value: consent() reported false while the SDK was working correctly. Making the returns
        // meaningful is what made this visible — before 3.0 every one of these drops was silent.
        //
        // 256 is chosen against the queue's own bulk-upload limit of 40: the collector hands off to
        // DeliveryMessages without blocking on disk or network, so the buffer only has to absorb a
        // burst, not a backlog.
        private val _eventReceiver =
            MutableSharedFlow<IntemptEvent>(replay = 10, extraBufferCapacity = 256)

        val eventReceiver: SharedFlow<IntemptEvent> = _eventReceiver

        private companion object {
            const val MILLIS_PER_SECOND = 1000
        }

        init {
            startEventCollection()
        }

        fun dispatchEvent(
            props: DispatchEventProps,
            serviceName: String,
        ) {
            if (!config.isUserOptIn) return
            val (eventName, entityName, event, type, context, view) = props
            logger.log("$serviceName | Received Event: $eventName; Type:$type")

            if (event != null) {
                emitEvent(
                    IntemptEvent(
                        name = eventName,
                        type = entityName,
                        payload = event,
                    ),
                )
                return
            }

            // launch + suspend rather than CompletableFuture.thenAccept. thenAccept is API 24,
            // and it was the last thing pinning the SDK's minSdk above 23.
            coroutineScope.launch {
                val payload =
                    handleEventType(
                        HandleEventTypeProps(
                            type = type,
                            entityName = entityName,
                            context = context,
                            view = view,
                        ),
                    )
                if (payload.isNotEmpty()) {
                    logger.log("AutoCapture | Successfully called function '${props.type}' on EventTypeHandler.")
                    emitEvent(
                        IntemptEvent(
                            name = eventName,
                            type = entityName,
                            payload = payload,
                        ),
                    )
                }
            }
        }

        fun emitEvent(event: IntemptEvent): Boolean {
            val isEmitted = _eventReceiver.tryEmit(event)
            logger.log("EventPool | Event is emitted: $isEmitted")
            return isEmitted
        }

        /**
         * Sends whatever is queued now, instead of waiting for the timer or the size trigger.
         *
         * [completion] receives the number of events the server accepted and runs on the delivery
         * worker thread, so a host app that touches UI from it must post to the main thread itself.
         */
        fun flush(completion: ((Int) -> Unit)? = null) {
            if (completion == null) {
                delivery.flush()
            } else {
                delivery.flush { delivered -> completion(delivered) }
            }
        }

        /**
         * Flush delay in **seconds**; 0 disables the timer.
         *
         * Seconds rather than the queue's native milliseconds because the cross-SDK contract
         * specifies seconds, and a bridge marshalling this value between platforms would
         * otherwise be off by a factor of a thousand in whichever direction nobody tested.
         */
        var flushInterval: Int
            get() = delivery.flushInterval / MILLIS_PER_SECOND
            set(seconds) {
                delivery.flushInterval = seconds * MILLIS_PER_SECOND
            }

        /**
         * Empties the durable queue without sending it.
         *
         * Used by `optOut()` and `reset()`. Consent records are unaffected because they never
         * enter this queue — they post directly to `/consents/data` — which is what allows an
         * opt-out to discard pending analytics while preserving the evidence of the decision
         * that caused it.
         */
        fun discardQueuedEvents() {
            logger.log("EventPool | Discarding queued events")
            delivery.emptyQueue()
        }

        fun subscribe(
            job: Job,
            callback: (value: IntemptEvent) -> Unit,
        ) {
            try {
                eventReceiverJob =
                    CoroutineScope(dispatcher + job).launch {
                        eventReceiver.collect { value ->
                            callback(value)
                        }
                    }
            } catch (e: Exception) {
                logger.log("Error during collection: ${e.message}")
            }
        }

        suspend fun getFeedData(
            id: String,
            quantity: Int,
            fields: List<String>,
            productId: String?,
        ): JsonObject? {
            val url = config.recommendationUrl(id)
            val body = JSONObject(intemptEvent.generateRecommendationBody(quantity, fields, productId))

            return http.post(url, body)?.bodyAsText().let {
                val jsonResponse = it?.let { it1 -> Json.parseToJsonElement(it1).jsonObject }
                logger.log("POST | Response: $jsonResponse")
                jsonResponse
            }
        }

        private fun startEventCollection() {
            logger.log("EventPoolManagerService | Started collecting events")
            subscribe(Job()) { event ->
                logger.log("IntemptCoreService | Received event of type: ${event.getEventType()}")

                when (event.getEventType()) {
                    EventType.Consent.value -> sendConsentEvent(event)
                    // Hands off and returns. delivery.enqueueEvent only posts a Message to
                    // the worker thread, so this collector never waits on disk or network.
                    //
                    // That matters more than it looks: _eventReceiver is a
                    // MutableSharedFlow(replay = 10) emitted via tryEmit, which returns false
                    // and drops the event when the buffer is full. There are two subscribers
                    // (here and SessionTracker), so a collector that blocked on I/O would
                    // cause silent drops upstream of the durable queue -- reintroducing the
                    // exact loss this work exists to eliminate. Keep this callback
                    // non-suspending and non-blocking.
                    else -> delivery.enqueueEvent(JSONObject(event.toFormated()))
                }
            }
        }

        /**
         * Dispatches to EventHandlers by name, reflectively. Returns the payload directly
         * instead of a CompletableFuture, which is what allowed minSdk to reach 23.
         *
         * The handlers are a mix: most are plain functions, installOrUpgrade suspends. The
         * previous version normalised that by wrapping everything in a CompletableFuture; this
         * one branches on isSuspend and uses callSuspend, which needs no API 24 type.
         */
        private suspend fun handleEventType(props: HandleEventTypeProps): Array<IntemptEventProvider> {
            logger.log("handleEventType | $props")

            return try {
                val handler = eventHandlers::class.declaredFunctions.find { it.name == props.type }

                if (handler == null) {
                    logger.log("AutoCapture | Function '${props.type}' not found on EventTypeHandler.")
                    emptyArray()
                } else {
                    handler.isAccessible = true
                    val result =
                        if (handler.isSuspend) {
                            handler.callSuspend(eventHandlers, props)
                        } else {
                            handler.call(eventHandlers, props)
                        }

                    @Suppress("UNCHECKED_CAST")
                    result as? Array<IntemptEventProvider> ?: emptyArray()
                }
            } catch (e: Exception) {
                logger.error(
                    "AutoCapture | Error invoking function '${props.type}' on EventTypeHandler: ${e.message}",
                )
                emptyArray()
            }
        }

        private fun sendConsentEvent(event: IntemptEvent) {
            val requestBodyJson = JSONObject(event.payload.first().toFormated())

            // Recorded unconditionally, before the network attempt, and not only in the
            // catch block below. Consent events bypass the durable queue entirely (they
            // post directly, above), so without this a failed -- or never-attempted, e.g.
            // process death mid-call -- send left no trace that the decision was ever made.
            // A compliance request for "this user's consent history" must be answerable
            // from local state, not from having trusted that the HTTP call succeeded.
            consentAudit?.record(requestBodyJson)

            coroutineScope.launch {
                try {
                    http.post(config.consentUrl, requestBodyJson)
                    logger.log("Successfully sent events to server")
                    lastDispatchTime = System.currentTimeMillis()
                } catch (e: Exception) {
                    logger.error("sendConsentEvent | Exception occurred while sending events: ${e.message}")
                }
            }
        }
    }
