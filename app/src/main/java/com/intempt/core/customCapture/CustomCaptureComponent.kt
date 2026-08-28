@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core.customCapture

import android.view.View
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.DatePicker
import android.widget.EditText
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RatingBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.TimePicker
import android.widget.ToggleButton
import com.intempt.core.eventModels.IntemptEvent
import com.intempt.core.internal.traced
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.ErrorReporter
import com.intempt.core.services.IntemptEventManagerService
import com.intempt.core.services.StorageManagerService
import com.intempt.core.services.UtilsService
import com.intempt.core.services.eventPool.EventPoolManagerService
import com.intempt.core.types.ConsentAction
import com.intempt.core.types.DefaultConfigs
import com.intempt.core.types.EventType
import com.intempt.core.types.FlagContext
import com.intempt.core.types.FlagDetail
import com.intempt.core.types.FlagReason
import com.intempt.core.types.IntemptError
import com.intempt.core.types.IntemptValue
import com.intempt.core.types.Product
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The capture surface behind [com.intempt.core.Intempt].
 *
 * Every capture method returns `Boolean`: was the event accepted into the queue. It is not a
 * delivery receipt — the queue is durable and asynchronous, so "delivered" is not knowable at the
 * call site. False means the event will never be sent: opted out, a value that cannot be
 * represented, a forbidden event name, or an emit that the buffer refused.
 *
 * Before 3.0 all of these returned `Unit` and swallowed every refusal into a log line. An
 * integrator could not distinguish a working call from one that had silently done nothing, which
 * is how a rejected `identify()` went unnoticed for weeks.
 *
 * The constructor is long because each parameter is a distinct collaborator; bundling them into a
 * holder would hide the graph Dagger is actually wiring.
 */
@Suppress("LongParameterList")
@Singleton
internal class CustomCaptureComponent
    @Inject
    constructor(
        private val srv: CustomCaptureService,
        private val config: ConfigManagerService,
        private val eventPool: EventPoolManagerService,
        private val intemptEvent: IntemptEventManagerService,
        private val utils: UtilsService,
        private val storage: StorageManagerService,
        private val errors: ErrorReporter,
    ) {
        fun isLoggingEnabled(): Boolean {
            return utils.withTryCatch("isLoggingEnabled fails") {
                config.isLoggingEnabled
            } ?: DefaultConfigs.IsLoggingEnabled.value
        }

        fun isOptedIn(): Boolean {
            return utils.withTryCatch("isOptedIn fails") {
                config.isUserOptIn
            } ?: DefaultConfigs.IsUserOptIn.value
        }

        fun hasOptedOut(): Boolean = !isOptedIn()

        fun getProfileId(): String = utils.withTryCatch("getProfileId fails") { storage.getProfileId() } ?: ""

        fun getSessionId(): String = utils.withTryCatch("getSessionId fails") { storage.getSessionId() } ?: ""

        fun enableLogging() {
            utils.withTryCatch("disableLogging fails") {
                srv.logger.log("Invoke enableLogging")
                config.isLoggingEnabled = true
            }
        }

        fun disableLogging() {
            utils.withTryCatch("disableLogging fails") {
                srv.logger.log("Invoke disableLogging")
                config.isLoggingEnabled = false
            }
        }

        fun optIn() {
            utils.withTryCatch("optIn fails") {
                srv.logger.log("Invoke optIn")
                config.isUserOptIn = true
                srv.logger.log("isOptedIn ${isOptedIn()}")
            }
        }

        /**
         * Opts out, and **discards what is already queued**.
         *
         * Setting the flag alone was not enough. Events captured before the objection sat in the
         * durable queue and were uploaded after it, so an opt-out did not stop the data it was
         * asked to stop — the SDK just stopped adding to the pile it was still sending.
         *
         * Consent records are deliberately spared: they never enter this queue (they post
         * directly to `/consents/data`) and their local audit trail is the evidence of the user's
         * decision, which is the one thing an opt-out must not erase.
         */
        fun optOut() {
            utils.withTryCatch("optOut fails") {
                srv.logger.log("Invoke optOut")
                config.isUserOptIn = false
                eventPool.discardQueuedEvents()
                srv.logger.log("isOptedIn ${isOptedIn()}")
            }
        }

        fun doNotCaptureText(view: View) {
            utils.withTryCatch("doNotCaptureText fails") {
                val canUse =
                    view is EditText ||
                        view is Spinner ||
                        view is ToggleButton ||
                        view is CheckBox ||
                        view is RadioButton ||
                        view is CompoundButton ||
                        view is TextView ||
                        view is SeekBar ||
                        view is RatingBar ||
                        view is TimePicker ||
                        view is DatePicker ||
                        view is ListView

                if (canUse) {
                    srv.setDoNotCaptureTag(view)
                } else {
                    srv.logger.error(
                        "Can't accept view of type ${view.javaClass.name}. Supported types are: " +
                            "EditText, Spinner, ToggleButton, CheckBox, RadioButton, CompoundButton, " +
                            "TextView, SeekBar, RatingBar, TimePicker, DatePicker, ListView.",
                    )
                }
            }
        }

        fun identify(
            userId: String,
            eventTitle: String? = null,
            userAttributes: Map<String, IntemptValue>? = null,
            data: Map<String, IntemptValue>? = null,
        ): Boolean =
            capture("identify") {
                if (!srv.isIdentifyValid(userId, eventTitle)) return@capture null
                if (!valuesRepresentable(userAttributes, data)) return@capture null

                IntemptEvent(
                    name = eventTitle ?: "Identify",
                    type = EventType.Identify.value,
                    payload =
                        intemptEvent.generateIdentifyEventPayload(
                            userId,
                            userAttributes,
                            data,
                        ),
                )
            }

        fun group(
            accountId: String,
            eventTitle: String? = null,
            accountAttributes: Map<String, IntemptValue>? = null,
        ): Boolean =
            capture("group") {
                if (!srv.isGroupValid(accountId, eventTitle)) return@capture null
                if (!valuesRepresentable(accountAttributes)) return@capture null

                IntemptEvent(
                    // "Group", not "Identify". This was copy-pasted from identify() above, so
                    // every group() call made without an explicit eventTitle arrived at the
                    // ingestion endpoint named "Identify" while carrying type "group" — the
                    // name and the type disagreed, and any analysis grouping on the event name
                    // silently folded account events in with user identification.
                    name = eventTitle ?: "Group",
                    type = EventType.Group.value,
                    payload =
                        intemptEvent.generateGroupEventPayload(
                            accountId,
                            accountAttributes,
                        ),
                )
            }

        fun track(
            eventTitle: String,
            data: Map<String, IntemptValue>,
        ): Boolean =
            capture("track") {
                if (!srv.isTrackValid(eventTitle)) return@capture null
                if (!valuesRepresentable(data)) return@capture null

                IntemptEvent(
                    name = eventTitle,
                    type = EventType.Track.value,
                    // The build half of the hot path: property collection plus JSON. Separated
                    // from Intempt.trackEnqueue so a regression can be attributed to payload
                    // construction rather than to the handoff.
                    payload = traced("Intempt.trackPayload") { intemptEvent.generateTrackEventPayload(data) },
                )
            }

        // Frozen by the contract — see Intempt.record.
        @Suppress("LongParameterList")
        fun record(
            eventTitle: String,
            userId: String? = null,
            accountId: String? = null,
            data: Map<String, IntemptValue>? = null,
            userAttributes: Map<String, IntemptValue>? = null,
            accountAttributes: Map<String, IntemptValue>? = null,
        ): Boolean =
            capture("record") {
                if (!srv.isTrackValid(eventTitle)) return@capture null
                if (!valuesRepresentable(data, userAttributes, accountAttributes)) return@capture null

                IntemptEvent(
                    name = eventTitle,
                    type = EventType.Record.value,
                    payload =
                        intemptEvent.generateRecordEventPayload(
                            accountId,
                            userId,
                            accountAttributes,
                            userAttributes,
                            data,
                        ),
                )
            }

        fun alias(
            userId: String,
            anotherUserId: String,
        ): Boolean =
            capture("alias") {
                IntemptEvent(
                    // "Alias", not "Identify". Third instance of the same copy-paste in this
                    // file: group() had it too. An alias event arriving named "Identify" while
                    // carrying type "alias" makes name and type disagree, so anything grouping
                    // by event name folds identity merges in with user identification.
                    name = "Alias",
                    type = EventType.Alias.value,
                    payload = intemptEvent.generateAliasEventPayload(userId, anotherUserId),
                )
            }

        /**
         * Records a consent decision. Three behaviours here are contractual, not incidental.
         *
         * **It transmits even when opted out.** Every other capture method returns early on
         * `!isUserOptIn`, and consent did too — which meant a user who had opted out could not
         * withdraw consent, because the withdrawal was the thing being suppressed. That inverts
         * what an opt-out is for.
         *
         * **`reject` opts out and `accept` opts in.** The two settings were previously
         * independent, so an app could record a rejection and keep collecting.
         *
         * **It bypasses the durable queue**, posting straight to `/consents/data` outside the
         * `/track` envelope — see `EventPoolManagerService.sendConsentEvent`, which also writes
         * the local audit record before the network attempt.
         */
        fun consent(
            action: ConsentAction,
            validUntil: Long,
            email: String? = null,
            message: String? = null,
            category: String? = null,
        ): Boolean {
            val accepted =
                utils.withTryCatch("consent fails") {
                    srv.logger.log("Invoke consent")

                    val newEvent =
                        IntemptEvent(
                            name = "Consent",
                            type = EventType.Consent.value,
                            payload =
                                intemptEvent.generateConsentEventPayload(
                                    action.wireValue,
                                    email,
                                    message,
                                    category,
                                    validUntil,
                                    config.sourceId,
                                ),
                        )

                    eventPool.emitEvent(newEvent)
                } ?: false

            if (!accepted) return false

            // After the emit, not before: opting out discards the queue, and doing that first
            // would be racing the consent record's own path off the device.
            when (action) {
                ConsentAction.ACCEPT -> optIn()
                ConsentAction.REJECT -> optOut()
            }

            return true
        }

        /**
         * Rotates the anonymous identity, keeping the queue.
         *
         * Runs regardless of opt-out state. It used to return early when opted out, which left
         * the previous user's `profileId` in place on a shared device — the exact inheritance
         * logging out exists to prevent, and most likely to matter for someone who had objected.
         */
        fun logOut() {
            utils.withTryCatch("logOut fails") {
                srv.logger.log("Invoke logOut")
                srv.logoutHandler()
            }
        }

        /** Rotates the anonymous identity **and** discards queued events. */
        fun reset() {
            utils.withTryCatch("reset fails") {
                srv.logger.log("Invoke reset")
                eventPool.discardQueuedEvents()
                srv.logoutHandler()
            }
        }

        fun flush(completion: ((Int) -> Unit)? = null) {
            utils.withTryCatch("flush fails") {
                srv.logger.log("Invoke flush")
                eventPool.flush(completion)
            }
        }

        var flushInterval: Int
            get() = eventPool.flushInterval
            set(value) {
                eventPool.flushInterval = value
            }

        fun productAdd(
            productId: String,
            quantity: Int,
        ): Boolean = productEvent("productAdd", "Added to cart", listOf(Product(productId, quantity)))

        fun productOrdered(products: List<Product>): Boolean {
            return productEvent("productOrdered", "Product ordered", products)
        }

        fun productView(productId: String): Boolean {
            return productEvent("productView", "Product viewed", listOf(Product(productId)))
        }

        /**
         * Internal. NOT public, deliberately.
         *
         * It returns a reason, and the platform does not send one: a held-back person's experience is
         * absent from the evaluation response entirely rather than present with a cause. So every
         * reason would read OFF — including for someone who WAS targeted and did receive the variant.
         * That is a wrong answer, not a missing one, and a method whose only job is explaining why
         * must not guess.
         *
         * [variation] uses it for the value, which is correct either way. It becomes public when the
         * serving contract carries a reason.
         */
        internal suspend fun variationDetailInternal(
            key: String,
            context: FlagContext,
            defaultValue: Any?,
        ): FlagDetail {
            if (key.isBlank()) {
                srv.logger.error("variation | key must not be blank")
                return FlagDetail(defaultValue, FlagReason.OFF)
            }
            // One exit for both outcomes rather than an early return for the miss:
            // detekt's ReturnCount caps a function at two, and an absent choice is not
            // an error path — it is simply the other answer.
            val choice =
                eventPool.chooseFlags(context, listOf(key))
                    .firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull == key }

            return if (choice == null) {
                FlagDetail(defaultValue, FlagReason.OFF)
            } else {
                val body = choice["body"]
                FlagDetail(
                    value = body?.let { unwrap(it) } ?: defaultValue,
                    reason = FlagReason.fromWire(choice["reason"]?.jsonPrimitive?.contentOrNull),
                )
            }
        }

        suspend fun allFlags(context: FlagContext): Map<String, Any?> =
            eventPool.chooseFlags(context, null).mapNotNull { choice ->
                val name = choice["name"]?.jsonPrimitive?.contentOrNull
                if (name.isNullOrBlank()) null else name to choice["body"]?.let { unwrap(it) }
            }.toMap()

        /** JSON to a Kotlin value the caller can branch on, with types preserved. */
        private fun unwrap(element: JsonElement): Any? =
            when (element) {
                is JsonNull -> null
                is JsonPrimitive ->
                    when {
                        element.isString -> element.content
                        element.booleanOrNull != null -> element.boolean
                        element.longOrNull != null -> element.long
                        element.doubleOrNull != null -> element.double
                        else -> element.content
                    }
                else -> element
            }

        suspend fun products(
            feedId: String,
            count: Int,
            fields: List<String>,
            productId: String?,
        ): JsonObject? {
            return eventPool.getFeedData(feedId, count, fields, productId)
        }

        private fun productEvent(
            caller: String,
            eventName: String,
            products: List<Product>,
        ): Boolean =
            capture(caller) {
                if (!srv.isProductListValid(products)) return@capture null

                IntemptEvent(
                    name = eventName,
                    type = EventType.Product.value,
                    payload = intemptEvent.generateProductEventPayload(products),
                )
            }

        /**
         * The one path every non-consent capture takes: opt-out check, build, emit, report.
         *
         * Each method used to repeat `if (!config.isUserOptIn) return` and each returned `Unit`
         * from inside `withTryCatch`, so a builder that threw and a builder that refused were
         * indistinguishable from a successful one. [build] returning null means refused;
         * `withTryCatch` returning null means it threw. Both are false, and neither reaches the
         * host app as an exception.
         */
        private fun capture(
            caller: String,
            build: () -> IntemptEvent?,
        ): Boolean =
            utils.withTryCatch("$caller fails") {
                if (!config.isUserOptIn) {
                    errors.report(IntemptError.OptedOut(caller))
                    return@withTryCatch false
                }

                val event = build() ?: return@withTryCatch false
                srv.logger.log("Invoke $caller")
                eventPool.emitEvent(event)
            } ?: false

        /**
         * Rejects NaN, infinity and anything nested inside a list or map that is one of those.
         *
         * These are not representable in JSON. Serialized unchecked they produce a body the
         * gateway rejects — and it rejects the **whole batch**, so one bad number loses every
         * event queued alongside it. Refusing at the call site costs one event and names the key.
         */
        private fun valuesRepresentable(vararg maps: Map<String, IntemptValue>?): Boolean {
            val invalid =
                maps.filterNotNull().flatMap { map ->
                    map.filterValues { !it.isValid() }.keys
                }

            invalid.forEach { errors.report(IntemptError.InvalidPropertyValue(it)) }
            return invalid.isEmpty()
        }
    }
