@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core

import android.view.View
import com.intempt.core.intemptCore.IntemptCoreService
import com.intempt.core.types.AutocaptureOptions
import com.intempt.core.types.AutomaticEventsOptions
import com.intempt.core.types.ConsentAction
import com.intempt.core.types.FeedFields
import com.intempt.core.types.FlagContext
import com.intempt.core.types.FlagDetail
import com.intempt.core.types.IntemptError
import com.intempt.core.types.IntemptValue
import com.intempt.core.types.Product
import kotlinx.serialization.json.JsonObject

/**
 * One running SDK instance, bound to one set of credentials.
 *
 * The SDK was a singleton, which the cross-SDK contract explicitly rejects: it makes two Intempt
 * projects in one app impossible, and it makes tests share state with each other. Get one from
 * [Intempt.initialize], [Intempt.mainInstance] or [Intempt.instance].
 *
 * Instances are isolated on disk as well as in memory — separate `SharedPreferences`, separate
 * event queue, separate consent audit log — so a second instance cannot inherit the first's
 * `profileId` or send its events under the wrong credentials.
 *
 * Every method here is also available as a static on [Intempt], where it addresses the `"default"`
 * instance. A single-project app never needs to touch this type.
 */
@Suppress("TooManyFunctions")
class IntemptInstance internal constructor(
    /** The name this instance was initialised under. `"default"` unless one was given. */
    val name: String,
    internal val core: IntemptCoreService,
) {
    // ------------------------------------------------------------------------ capture

    /** Records a custom event named [eventTitle] with the given [data] properties. */
    @JvmOverloads
    fun track(
        eventTitle: String,
        data: Map<String, IntemptValue> = emptyMap(),
    ): Boolean = core.capture.track(eventTitle, data)

    /** Associates the current session with [userId]. */
    @JvmOverloads
    fun identify(
        userId: String,
        eventTitle: String? = null,
        userAttributes: Map<String, IntemptValue>? = null,
        data: Map<String, IntemptValue>? = null,
    ): Boolean = core.capture.identify(userId, eventTitle, userAttributes, data)

    /** Associates the current session with an account/organization [accountId]. */
    @JvmOverloads
    fun group(
        accountId: String,
        eventTitle: String? = null,
        accountAttributes: Map<String, IntemptValue>? = null,
    ): Boolean = core.capture.group(accountId, eventTitle, accountAttributes)

    /** Merges the profile previously known as [userId] into [anotherUserId]. */
    fun alias(
        userId: String,
        anotherUserId: String,
    ): Boolean = core.capture.alias(userId, anotherUserId)

    /** Records [eventTitle] attributed to [userId]/[accountId]. Argument order is contract-frozen. */
    @Suppress("LongParameterList") // frozen by the contract; see Intempt.record
    @JvmOverloads
    fun record(
        eventTitle: String,
        userId: String? = null,
        accountId: String? = null,
        data: Map<String, IntemptValue>? = null,
        userAttributes: Map<String, IntemptValue>? = null,
        accountAttributes: Map<String, IntemptValue>? = null,
    ): Boolean = core.capture.record(eventTitle, userId, accountId, data, userAttributes, accountAttributes)

    /** Records that [quantity] units of [productId] were added to the cart. */
    fun productAdd(
        productId: String,
        quantity: Int,
    ): Boolean = core.capture.productAdd(productId, quantity)

    /** Records that [productId] was viewed. */
    fun productView(productId: String): Boolean = core.capture.productView(productId)

    /** Records a completed order. */
    fun productOrdered(products: List<Product>): Boolean = core.capture.productOrdered(products)

    /** Records a consent decision. Transmits even when opted out; `REJECT` opts out, `ACCEPT` opts in. */
    @JvmOverloads
    fun consent(
        action: ConsentAction,
        validUntil: Long,
        email: String? = null,
        message: String? = null,
        category: String? = null,
    ): Boolean = core.capture.consent(action, validUntil, email, message, category)

    // ------------------------------------------------------------- identity and lifecycle

    /** The device-minted anonymous profile id. */
    fun getProfileId(): String = core.capture.getProfileId()

    /** The current session id, or "" when no session has started. */
    fun getSessionId(): String = core.capture.getSessionId()

    /** Rotates the anonymous identity, **keeping** whatever is queued. */
    fun logOut() = core.capture.logOut()

    /** Rotates the anonymous identity **and discards** queued events. */
    fun reset() = core.capture.reset()

    // ----------------------------------------------------------------------- opt in / out

    /** Resumes event capture after [optOut]. */
    fun optIn() = core.capture.optIn()

    /** Stops event capture **and discards what is already queued.** Consent records survive. */
    fun optOut() = core.capture.optOut()

    /** True when the user has opted out. */
    fun hasOptedOut(): Boolean = core.capture.hasOptedOut()

    /** The inverse of [hasOptedOut]. */
    fun isOptedIn(): Boolean = core.capture.isOptedIn()

    // --------------------------------------------------------------------------- delivery

    /** Sends whatever is queued now. [completion] receives the count the server accepted. */
    @JvmOverloads
    fun flush(completion: ((Int) -> Unit)? = null) = core.capture.flush(completion)

    /** Seconds between automatic flushes; 0 disables the timer. */
    var flushInterval: Int
        get() = core.capture.flushInterval
        set(value) {
            core.capture.flushInterval = value
        }

    // ------------------------------------------------------------------ errors and options

    /**
     * Called with an [IntemptError] whenever the SDK refuses or fails something.
     *
     * The `Boolean` a capture method returns says *whether*; this says *why*. Runs on the thread
     * the failure happened on — the caller's for a refused `track()`, the delivery worker's for a
     * transport failure — so post to the main thread yourself if it touches UI. Pass null to clear.
     */
    fun setErrorListener(listener: ((IntemptError) -> Unit)?) = core.errors.setListener(listener)

    /**
     * Lifecycle facts the SDK emits without instrumentation. Settable, and read at the next event.
     *
     * Defaults are sessions on, version changes off, app-state changes off. The SDK used to emit
     * all three unconditionally; an SDK that silently emits events nobody asked for is how an
     * event-volume bill surprises someone.
     */
    var automaticEvents: AutomaticEventsOptions
        get() = core.config.automaticEventsOptions
        set(value) {
            core.config.automaticEventsOptions = value
        }

    /** UI instrumentation. Inert until [Autocapture.start] — see the type's own note. */
    val autocapture: Autocapture = Autocapture(core)

    /**
     * The view-layer hooks.
     *
     * **Nothing is installed until [start].** That is contractual rather than stylistic: an SDK may
     * not instrument a host app's UI as a side effect of being initialised. `initialize()` calls
     * [start] for you only when `assets/intempt-config.json` sets `isAutoCaptureEnabled` — an
     * explicit request written by the host app, not a default the SDK assumed.
     */
    class Autocapture internal constructor(private val core: IntemptCoreService) {
        /** Sets options without starting. Safe to call before or after [start]. */
        fun configure(options: AutocaptureOptions) {
            core.config.autocaptureOptions = options
        }

        /** Current options. */
        val options: AutocaptureOptions get() = core.config.autocaptureOptions

        /** Installs the hooks. Idempotent — returns false when they were already on. */
        @JvmOverloads
        fun start(options: AutocaptureOptions? = null): Boolean = core.autoCapture.startAutocapture(options)

        /** Uninstalls the hooks. Idempotent — returns false when they were already off. */
        fun stop(): Boolean = core.autoCapture.stopAutocapture()

        /** Whether the hooks are currently installed. */
        val isRunning: Boolean get() = core.autoCapture.isAutocaptureRunning
    }

    // ------------------------------------------------------------------------- everything else

    /**
     * Up to [count] product recommendations from feed [feedId], returning only [fields].
     *
     * Named `products` rather than `recommendation` as of 3.0. The capability is unchanged — the
     * same `/feeds/{feedId}/data` call — and the contract picked one name for it across every SDK,
     * because a bridge cannot be thin while the same request is spelled two ways.
     *
     * Never widen [fields] by omission. [FeedFields.DEFAULT] exists because an unfielded request
     * returns every catalog column including raw ML embedding vectors: 222,919 bytes against 503
     * for the same 10 products.
     *
     * Returns null when the request fails. The feed answers only for a profile the platform has
     * already ingested, and it returns the same "USER … is not found" for a wrong feed id and a
     * wrong profile — so a null here means one of the two, not "the feed is broken".
     */
    @JvmOverloads
    suspend fun products(
        feedId: String,
        count: Int = 10,
        fields: List<String> = FeedFields.DEFAULT,
        productId: String? = null,
    ): JsonObject? = core.capture.products(feedId, count, fields, productId)

    /** The value assigned for [key], or [defaultValue] when the service did not answer. */
    @JvmOverloads
    suspend fun variation(
        key: String,
        context: FlagContext = FlagContext(),
        defaultValue: Any?,
    ): Any? = variationDetail(key, context, defaultValue).value

    /** As [variation], plus why. */
    @JvmOverloads
    suspend fun variationDetail(
        key: String,
        context: FlagContext = FlagContext(),
        defaultValue: Any?,
    ): FlagDetail = core.capture.variationDetail(key, context, defaultValue)

    /** Every key assigned to this person, in one call. */
    @JvmOverloads
    suspend fun allFlags(context: FlagContext = FlagContext()): Map<String, Any?> = core.capture.allFlags(context)

    /** Excludes [view] from autocapture so its text is never recorded. Android-only. */
    fun doNotCaptureText(view: View) = core.capture.doNotCaptureText(view)

    /** Enables the SDK's diagnostic log output. Separate from event capture. */
    fun startLogging() = core.capture.enableLogging()

    /** Disables the SDK's diagnostic log output. */
    fun stopLogging() = core.capture.disableLogging()

    /** Whether diagnostic logging is on. */
    fun isLoggingEnabled(): Boolean = core.capture.isLoggingEnabled()

    override fun toString(): String = "IntemptInstance(name=$name)"
}
