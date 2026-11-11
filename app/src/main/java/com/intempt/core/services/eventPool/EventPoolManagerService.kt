@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core.services.eventPool
import com.intempt.core.autocapture.BaseComponent
import com.intempt.core.eventModels.IntemptEvent
import com.intempt.core.internal.traced
import com.intempt.core.queue.ConsentAuditLog
import com.intempt.core.queue.DeliveryMessages
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.HttpManagerService
import com.intempt.core.services.IntemptEventManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.PostOutcome
import com.intempt.core.types.ChooseResult
import com.intempt.core.types.DispatchEventProps
import com.intempt.core.types.EventType
import com.intempt.core.types.FlagContext
import com.intempt.core.types.FlagErrorReporter
import com.intempt.core.types.HandleEventTypeProps
import com.intempt.core.types.IntemptEventProvider
import com.intempt.core.types.chooseResultOf
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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

        // EventHandlers no longer takes a dispatcher: its one dispatcher-sensitive call
        // (fetching the FCM token) moved behind PushBridge.registerTokenIfPresent(), which
        // wraps its own reflective call in withContext(Dispatchers.IO) internally.
        private val eventHandlers = EventHandlers(logger, intemptEvent)
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

        // Events accepted by emitEvent that the pool's collector has not yet handed to
        // DeliveryMessages. flush waits for this to reach 0 so a flush right after record()
        // includes that event (INT-7447). Only the pool collector decrements: SessionTracker is a
        // second subscriber on the same flow and must not.
        private val pendingHandoffs = MutableStateFlow(0)

        private companion object {
            const val MILLIS_PER_SECOND = 1000
            const val MAX_CONSENT_REJECTIONS = 3
            const val CONSENT_MAX_AGE_DAYS = 30L
            private const val BACKOFF_DRAIN_SLACK_MS = 1_000L

            // Bounds flush's wait for in-flight hand-offs: a collector that died or stalls must
            // cost one late flush, never a flush that does not happen.
            const val FLUSH_HANDOFF_WAIT_MS = 2_000L
            val CONSENT_MAX_AGE_MS = java.util.concurrent.TimeUnit.DAYS.toMillis(CONSENT_MAX_AGE_DAYS)
        }

        // Backoff for resending pending consents; replaced in tests to control the clock.
        internal var consentRetry = ConsentRetryPolicy()

        // Clock for the consent age limit; replaced in tests.
        internal var consentClock: () -> Long = System::currentTimeMillis
        private val consentDrainLock = Mutex()

        // Consecutive non-retryable answers (see isConsentRetryable) for the head consent, in memory only.
        private var rejectedId = -1L
        private var rejectedCount = 0

        /**
         * Sends pending consents oldest first, one request at a time (INT-3904). Stops at the first
         * failure so a later decision never overtakes an earlier one; a consent confirmed with 2xx
         * is marked delivered and never sent again. The backend has no idempotency key, so a send
         * whose answer was lost (timeout, status 0) is retried and can arrive twice: delivery is
         * at-least-once, and a duplicate consent row is the accepted cost of never losing one.
         * Statuses the event queue treats as retryable (transport errors, 401, 402, 403, 404, 408,
         * 429, 5xx) keep the record; any other non-2xx status is a rejection and the record is
         * dropped after [MAX_CONSENT_REJECTIONS] consecutive ones (see [isConsentRetryable]), so
         * one bad record cannot block every later consent.
         */
        @Suppress("ReturnCount") // guard clauses; the drain loop reads clearer flat
        internal suspend fun drainConsents() {
            val audit = consentAudit ?: return
            consentDrainLock.withLock {
                while (!consentRetry.requestNotAllowed) {
                    val pending = audit.oldestPending() ?: return
                    if (consentClock() - pending.createdAt > CONSENT_MAX_AGE_MS) {
                        // Kept-forever statuses (401/402/403/404...) must not park the queue head for ever.
                        audit.markDropped(pending.id)
                        logger.error(
                            "sendConsentEvent | consent ${pending.id} dropped: undelivered for over " +
                                "$CONSENT_MAX_AGE_DAYS days",
                        )
                        continue
                    }
                    val outcome = http.postForStatus(config.consentUrl, pending.payload)
                    if (!applyConsentOutcome(audit, pending.id, outcome)) return
                }
            }
        }

        /** Returns true when the drain may continue with the next record. */
        @Suppress("ReturnCount") // one exit per outcome class
        private fun applyConsentOutcome(
            audit: ConsentAuditLog,
            id: Long,
            outcome: PostOutcome,
        ): Boolean {
            val status = outcome.status
            if (outcome.isSuccess) {
                audit.markDelivered(id)
                consentRetry.recordSuccess()
                lastDispatchTime = System.currentTimeMillis()
                return true
            }
            consentRetry.recordFailure(outcome.retryAfterMs)
            if (!isConsentRetryable(status)) {
                rejectedCount = if (rejectedId == id) rejectedCount + 1 else 1
                rejectedId = id
                if (rejectedCount >= MAX_CONSENT_REJECTIONS) {
                    audit.markDropped(id)
                    logger.error(
                        "sendConsentEvent | consent $id dropped after $rejectedCount rejections with $status",
                    )
                    rejectedId = -1L
                    rejectedCount = 0
                    // The server is answering; the record was the problem. Do not back off the
                    // consents behind it.
                    consentRetry.recordSuccess()
                    return true
                }
            } else {
                rejectedId = -1L
                rejectedCount = 0
            }
            logger.error(
                "sendConsentEvent | consent event was NOT delivered to ${config.consentUrl} (status $status). " +
                    "It stays pending and is retried; the platform did not receive it.",
            )
            return false
        }

        /**
         * Starts a drain of pending consents without blocking the caller. Used at init, on [flush]
         * and when the app goes to the background, so a consent that failed earlier is retried
         * even when the host never calls [flush]. Respects the backoff: a drain inside the window
         * is a no-op. A failure is logged; cancellation is rethrown so it is never swallowed.
         */
        fun drainConsentsAsync() {
            if (consentAudit == null) return
            coroutineScope.launch {
                try {
                    drainConsents()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("sendConsentEvent | consent drain failed: ${e.message}")
                }
            }
        }

        private var backoffDrainJob: Job? = null

        init {
            startEventCollection()
            // Consents left pending by an earlier run (failed send, process death) go out now.
            drainConsentsAsync()
        }

        /**
         * Returns whether the event was accepted, false when the user is opted out. A caller that
         * records the event as sent (the push-token dedupe) must not do so for a dropped one
         * (INT-5964). Accepted means handed to the build; the payload itself is built async.
         */
        @Suppress("ReturnCount") // a guard and two paths; nesting them would hide the early drop
        fun dispatchEvent(
            props: DispatchEventProps,
            serviceName: String,
        ): Boolean {
            if (!config.isUserOptIn) return false
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
                return true
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
                            targetIdOverride = props.targetIdOverride,
                            installReason = props.installReason,
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
            return true
        }

        fun emitEvent(event: IntemptEvent): Boolean {
            // The handoff to the durable queue: the SharedFlow emit that every capture call ends
            // in. Named for track because track is what drives it thousands of times a session;
            // identify/group/record and autocapture emits go through the same section, so read
            // the section's count alongside the workload that produced it.
            // Counted before tryEmit, not after: the collector can run and decrement before
            // tryEmit even returns.
            pendingHandoffs.update { it + 1 }
            val isEmitted = traced("Intempt.trackEnqueue") { _eventReceiver.tryEmit(event) }
            if (!isEmitted) pendingHandoffs.update { it - 1 }
            logger.log("EventPool | Event is emitted: $isEmitted")
            return isEmitted
        }

        /**
         * Builds an event off the caller's thread and writes it straight into the durable queue,
         * bypassing the in-memory flow (INT-5964).
         *
         * For the push-token release on logOut()/reset(), the one event whose loss is a privacy
         * defect rather than an analytics gap: if it never arrives, the logged-out user's token stays
         * on their profile and their push keeps reaching the device. [emitEvent] is a `tryEmit` on a
         * bounded flow and drops when the buffer is full, which a burst just before a logout can
         * cause. This path has no buffer to fill: [build] runs on [dispatcher] and the result goes
         * to [DeliveryMessages.enqueueEvent], which only posts to the delivery worker. The session
         * tracker does not see the event, which is right for one that belongs to the profile being
         * left. Whatever [build] needs from the profile being left it must capture before this call.
         *
         * A failure is logged as an error naming the event, since nothing retries it.
         */
        fun enqueueDurably(
            name: String,
            type: String,
            build: () -> Array<IntemptEventProvider>,
        ): Job =
            coroutineScope.launch {
                try {
                    val event = IntemptEvent(name = name, type = type, payload = build())
                    delivery.enqueueEvent(JSONObject(event.toFormated()))
                    logger.log("EventPool | '$name' written to the durable queue")
                } catch (e: Exception) {
                    logger.error("EventPool | '$name' was NOT queued and will not be sent: ${e.message}")
                }
            }

        /**
         * Sends whatever is queued now, instead of waiting for the timer or the size trigger.
         *
         * [completion] receives the number of events the server accepted and runs on the delivery
         * worker thread, so a host app that touches UI from it must post to the main thread itself.
         */
        fun flush(completion: ((Int) -> Unit)? = null) {
            drainConsentsAsync()
            // Launched rather than awaited, so a flush from the main thread never blocks on the
            // collector. delivery.enqueueEvent and delivery.flush post to the same FIFO worker
            // handler, so once the count is 0 every recorded event's enqueue is ahead of this.
            coroutineScope.launch {
                withTimeoutOrNull(FLUSH_HANDOFF_WAIT_MS) { pendingHandoffs.first { it <= 0 } }
                if (completion == null) {
                    delivery.flush()
                } else {
                    delivery.flush { delivered -> completion(delivered) }
                }
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

        /** Per instance, so the once-only auth log and the `onFlagError` hook are per instance too. */
        internal val flagErrors =
            FlagErrorReporter({ config.sourceId }, { logger.error(it) }, { logger.debug(it) })

        /**
         * Evaluate flags. Never throws on a service problem: a failure comes back as
         * [ChooseResult.error] with no choices, after it has been reported to [flagErrors].
         *
         * This is the entire reason a default value is required at the public surface: a network
         * failure, a 5xx or a timeout must resolve to the value the caller chose. A flag SDK that
         * throws when the service is unreachable takes the host app down with it, which is the
         * opposite of what a kill switch is for.
         */
        suspend fun chooseFlags(
            context: FlagContext,
            names: List<String>?,
        ): ChooseResult {
            val url = config.optimizationUrl
            val body = JSONObject(intemptEvent.generateChooseBody(context, names))

            val response = http.postChoose(url, body, HttpManagerService.CHOOSE_TIMEOUT_MS)
            val result = chooseResultOf(names?.singleOrNull(), response.status, response.body, response.timedOut)
            result.error?.let(flagErrors::report)
            return result
        }

        suspend fun getFeedData(
            id: String,
            quantity: Int,
            fields: List<String>,
            productId: String?,
            userId: String? = null,
        ): JsonObject? {
            val url = config.recommendationUrl(id)
            val body = JSONObject(intemptEvent.generateRecommendationBody(quantity, fields, productId, userId))

            return http.post(url, body)?.bodyAsText().let {
                val jsonResponse = it?.let { it1 -> Json.parseToJsonElement(it1).jsonObject }
                logger.log("POST | Response: $jsonResponse")
                jsonResponse
            }
        }

        private fun startEventCollection() {
            logger.log("EventPoolManagerService | Started collecting events")
            // UNDISPATCHED so the subscription exists before init returns. Otherwise events
            // emitted before the collector starts survive only in the 10 replay slots; the rest
            // are counted in pendingHandoffs and never handed off, and every flush would then
            // wait the full FLUSH_HANDOFF_WAIT_MS. Not via subscribe(): SessionTracker shares it and
            // must not get replay callbacks synchronously on its caller's thread.
            eventReceiverJob =
                CoroutineScope(dispatcher + Job()).launch(start = CoroutineStart.UNDISPATCHED) {
                    eventReceiver.collect { event ->
                        logger.log("IntemptCoreService | Received event of type: ${event.getEventType()}")

                        try {
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
                        } finally {
                            pendingHandoffs.update { it - 1 }
                        }
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

            // Persisted as pending before the network attempt, so a failed or interrupted send
            // (process death mid-call) is retried by drainConsents instead of being lost.
            // The attempt itself is still immediate: a drain right after recording sends this
            // decision, and anything older than it first.
            val recorded = consentAudit?.record(requestBodyJson) ?: -1L

            coroutineScope.launch {
                try {
                    if (recorded >= 0) {
                        drainConsents()
                        // A consent recorded inside a backoff window is saved but not yet sent:
                        // schedule one drain for when the window closes (one-shot, deduplicated).
                        backoffDrainJob =
                            coroutineScope.launchOnceAfter(
                                backoffDrainJob,
                                consentRetry.remainingMs().takeIf { it > 0L }?.plus(BACKOFF_DRAIN_SLACK_MS) ?: 0L,
                                { logger.error("sendConsentEvent | scheduled consent drain failed: ${it.message}") },
                            ) { drainConsents() }
                    } else {
                        // No usable local store: one best-effort attempt, as before INT-3904.
                        val outcome = http.postForStatus(config.consentUrl, requestBodyJson)
                        if (outcome.isSuccess) {
                            lastDispatchTime = System.currentTimeMillis()
                        } else {
                            logger.error("sendConsentEvent | consent event was NOT delivered to ${config.consentUrl}")
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("sendConsentEvent | Exception occurred while sending events: ${e.message}")
                }
            }
        }
    }
