@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core

import android.content.Context
import android.util.Log
import android.view.View
import com.intempt.core.intemptCore.DaggerIntemptCoreComponent
import com.intempt.core.intemptCore.IntemptCoreModule
import com.intempt.core.internal.PushBridge
import com.intempt.core.internal.traced
import com.intempt.core.types.AutocaptureOptions
import com.intempt.core.types.AutomaticEventsOptions
import com.intempt.core.types.ConsentAction
import com.intempt.core.types.FeedFields
import com.intempt.core.types.InstanceId
import com.intempt.core.types.IntemptCredentials
import com.intempt.core.types.IntemptRuntimeOptions
import com.intempt.core.types.IntemptError
import com.intempt.core.types.IntemptValue
import com.intempt.core.types.Product
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * The SDK's whole public surface.
 *
 * Shaped by the cross-SDK API contract in `intempt-swift@docs/SDK-API-CONTRACT.md`, which every
 * Intempt client SDK conforms to so a React Native or Flutter bridge stays thin and the same call
 * means the same thing on both platforms. 3.0 is a clean break to reach it; there are no
 * deprecated aliases because there are no Android integrations to keep working.
 *
 * Four properties are worth stating here because they change how you call this:
 *
 * - **Instances are named, and more than one can run.** Every static below addresses the
 *   `"default"` instance. A single-project app never needs to know that; an app talking to two
 *   Intempt projects gets an [IntemptInstance] from [initialize] and calls it directly. A
 *   singleton is not conformant — it makes two projects impossible and makes tests share state.
 * - **Every capture method returns `Boolean`** — was the event accepted into the queue. Not a
 *   delivery receipt. [setErrorListener] is how you learn *why* a `false` happened.
 * - **Attribute maps are typed.** [IntemptValue] instead of `String`, so `42` and `"42"` stay
 *   different all the way to the platform. [IntemptValue.mapOf] wraps a plain map.
 * - **Every entry point is safe to call when the SDK is not running.** Each one used to
 *   dereference a `lateinit` field, so a failed or absent `initialize()` turned the first `track()`
 *   into `UninitializedPropertyAccessException` inside the host app. Analytics is not worth a
 *   crash; a call made while disabled logs once and does nothing.
 */
object Intempt {
    private const val TAG = "Intempt"

    /**
     * Live instances by name.
     *
     * Concurrent because `initialize()` can be called from any thread and every capture call reads
     * this. A plain map would make a background `initialize()` racing a `track()` a
     * ConcurrentModificationException inside a host app — the crash class this facade exists to
     * avoid.
     */
    private val instances = ConcurrentHashMap<String, IntemptInstance>()

    /** True once the `"default"` instance is running. */
    @JvmStatic
    val isInitialized: Boolean
        get() = instances.containsKey(InstanceId.DEFAULT)

    // ---------------------------------------------------------------------- initialization

    /**
     * Starts the `"default"` instance, reading credentials from `assets/intempt-config.json`.
     *
     * @return true when the SDK is running.
     */
    @JvmStatic
    fun initialize(context: Context): Boolean = start(context, null, InstanceId.DEFAULT) != null

    /**
     * Starts the `"default"` instance with credentials supplied at runtime.
     *
     * The asset file remains supported and is still the documented path for a plain Android app,
     * but it must not be the only way in: a React Native or Flutter bridge receives credentials
     * from JavaScript and cannot ship native asset files on its users' behalf, and a white-label
     * app resolves a different project per tenant. [credentials] wins per field when supplied; the
     * file is the fallback.
     *
     * @return true when the SDK is running. False, never an exception, on bad credentials — read
     *   [IntemptCredentials.problems] first if you want to know what is wrong before calling.
     */
    @JvmStatic
    fun initialize(
        context: Context,
        credentials: IntemptCredentials?,
    ): Boolean = start(context, credentials, InstanceId.DEFAULT) != null

    /**
     * Starts a **named** instance and returns it, or null on failure.
     *
     * Instances are isolated on disk as well as in memory — separate `SharedPreferences`, event
     * queue and consent audit log — so a second instance cannot inherit the first's `profileId`
     * or send its events under the wrong credentials.
     *
     * Calling this twice with the same [instanceName] returns the existing instance rather than
     * building a second graph over the same storage.
     */
    @JvmStatic
    fun initialize(
        context: Context,
        credentials: IntemptCredentials?,
        instanceName: String,
    ): IntemptInstance? = start(context, credentials, instanceName, null)

    /**
     * Starts a **named** instance with options supplied at runtime, or null on failure.
     *
     * [options] overrides `assets/intempt-config.json` **per field**, exactly as [credentials]
     * does: a field set here wins, a field left null falls through to the asset file and then to
     * the default. An app that sets one option keeps every other value from its asset file.
     *
     * The asset file remains the documented setup for a plain Android app. This overload exists
     * for callers that have no asset file to edit — React Native, Flutter and any other bridge,
     * where the host app configures the SDK in JavaScript and cannot reach the native bundle's
     * assets. Before it, such a bridge could accept an option and silently drop it, which is worse
     * than not offering it.
     */
    @JvmStatic
    fun initialize(
        context: Context,
        credentials: IntemptCredentials?,
        instanceName: String,
        options: IntemptRuntimeOptions?,
    ): IntemptInstance? = start(context, credentials, instanceName, options)

    /** The `"default"` instance, or null when it is not running. */
    @JvmStatic
    fun mainInstance(): IntemptInstance? = instances[InstanceId.DEFAULT]

    /** The instance named [name], or null when it is not running. */
    @JvmStatic
    fun instance(name: String): IntemptInstance? = instances[name]

    // Seven exits, each a distinct refusal a caller can act on: already running, blank name,
    // bad credentials, unconfigured, construction failed, lost the race, success. Collapsing
    // them into one would mean tracking which of the seven happened in a variable.
    @Suppress("ReturnCount")
    private fun start(
        context: Context,
        credentials: IntemptCredentials?,
        instanceName: String,
        options: IntemptRuntimeOptions? = null,
    ): IntemptInstance? {
        instances[instanceName]?.let {
            Log.i(TAG, "Instance \"$instanceName\" is already initialized; returning it")
            return it
        }

        if (instanceName.isBlank()) {
            Log.e(TAG, "Initialization failed: instanceName is blank.")
            return null
        }

        if (credentials != null) {
            val problems = credentials.problems()
            if (problems.isNotEmpty()) {
                Log.e(TAG, "Initialization failed: ${problems.joinToString("; ")}. The SDK is disabled.")
                return null
            }
        }

        // The whole construction happens under the lock, not just the map write.
        //
        // An earlier version built the graph first and used the lock only to register it, with a
        // comment claiming that stopped a losing thread from leaving resources behind. It did not:
        // by the time the lock was taken the loser had already constructed DeliveryMessages, whose
        // constructor starts a HandlerThread and probes the network, and EventPoolManagerService,
        // whose init block starts a flow collector. The lock protected the map and nothing else,
        // and the comment said otherwise — which is worse than no comment.
        //
        // Holding it across construction is cheap here: initialize() is called once per process at
        // application startup, and the double-checked read above means a caller that finds an
        // existing instance never reaches this block at all.
        synchronized(instances) {
            instances[instanceName]?.let {
                Log.i(TAG, "Instance \"$instanceName\" was initialized concurrently; using the first")
                return it
            }
            return build(context, credentials, instanceName, options)
        }
    }

    /**
     * Builds and registers one instance. Callers hold the [instances] monitor.
     *
     * The work itself is [buildTraced]; this only wraps it in a trace section.
     */
    private fun build(
        context: Context,
        credentials: IntemptCredentials?,
        instanceName: String,
        options: IntemptRuntimeOptions?,
    ): IntemptInstance? =
        // The whole of init is one named trace section, and each expensive step inside it is its
        // own. Macrobenchmark's TraceSectionMetric reads these out of the Perfetto trace, which is
        // the only way to see the SDK's own cost: whole-app timeToInitialDisplay is ~650ms on CI
        // and ~420ms locally for identical code, so a ~100ms init is well inside the noise.
        //
        // android.os.Trace directly (API 18+, minSdk 23) rather than androidx.tracing: a new
        // dependency would spend the method-count headroom this instrumentation exists to protect.
        traced("Intempt.initialize") {
            buildTraced(context, credentials, instanceName, options)
        }

    /**
     * The body of [build]. Callers hold the [instances] monitor.
     *
     * Three exits: unconfigured, construction threw, success. Each is a distinct outcome a caller
     * can act on, and merging them would mean carrying which one happened in a variable.
     */
    @Suppress("ReturnCount")
    private fun buildTraced(
        context: Context,
        credentials: IntemptCredentials?,
        instanceName: String,
        options: IntemptRuntimeOptions?,
    ): IntemptInstance? {
        val instance: IntemptInstance
        try {
            val component =
                traced("Intempt.daggerGraph") {
                    DaggerIntemptCoreComponent.factory()
                        .create(
                            IntemptCoreModule(
                                context, credentials, options, InstanceId(instanceName),
                            ),
                        )
                }

            // Credentials are read lazily, so Dagger wires up perfectly against a config asset that
            // does not exist. Before this check, initialize() returned true for an app with no
            // intempt-config.json at all: the SDK reported itself healthy, queued events, and
            // posted them with no Authorization header, so every batch 401'd and was dropped.
            //
            // The whole reason this returns a Boolean is that it used to return Unit and a host app
            // had no way to tell a working SDK from a dead one. Returning true here made that
            // signal a lie.
            val config = traced("Intempt.config") { component.config() }
            if (!config.isConfigured) {
                Log.e(
                    TAG,
                    "Initialization failed: no ${config.missingCredentials().joinToString(", ")}. " +
                        "Pass IntemptCredentials to initialize(), or add intempt-config.json to " +
                        "src/main/assets. The SDK is disabled and all calls are no-ops.",
                )
                return null
            }

            instance = IntemptInstance(instanceName, traced("Intempt.initService") { component.initService() })
        } catch (e: Throwable) {
            // Throwable, not Exception. An analytics SDK must never take the host app down, and
            // the failures that actually do are Errors rather than Exceptions: a
            // NoClassDefFoundError from a dependency that is invalid on this API level went
            // straight through `catch (e: Exception)` and killed the app on launch.
            Log.e(TAG, "Initialization failed; the SDK is disabled and all calls are no-ops", e)
            return null
        }

        // A plain put, under the caller's lock. NOT Map.putIfAbsent: that is API 24 as a Map
        // default method against a minSdk of 23, and Kotlin resolves it through MutableMap rather
        // than ConcurrentHashMap's own API-1 override — so it compiles, passes lint, and throws
        // NoSuchMethodError on a real API 23 device. AnimalSniffer caught it; build.gradle.kts
        // already lists this exact method as one of three minSdk crashes that reached this branch.
        instances[instanceName] = instance

        // Only after registration, so a screen view emitted by the hooks finds its instance.
        traced("Intempt.autocapture") { instance.core.startAutocaptureIfConfigured() }

        // Push notifications are OPTIONAL and live in the separate :push module. This is a
        // presence-gated reflection call rather than a direct dependency — :app cannot depend on
        // :push (the dependency direction is :push -> :app) — so it degrades silently to a no-op
        // when the host app hasn't added :push, and also when it has but hasn't configured
        // Firebase (google-services plugin + google-services.json). Never fail core analytics
        // init over a missing or absent push setup.
        traced("Intempt.pushBridge") { PushBridge.initializeIfPresent(context) }

        return instance
    }

    /**
     * The `"default"` instance, or null with one log line when it is not running.
     *
     * The single guard every static below goes through, so a call made before `initialize()` is a
     * no-op rather than a crash in someone's Activity.
     */
    private fun main(caller: String): IntemptInstance? {
        val instance = instances[InstanceId.DEFAULT]
        if (instance == null) {
            Log.w(TAG, "$caller ignored: the SDK is not initialized. Call Intempt.initialize(context) first.")
        }
        return instance
    }

    // ------------------------------------------------------------------------ capture

    /** Records a custom event named [eventTitle] with the given [data] properties. */
    @JvmStatic
    @JvmOverloads
    fun track(
        eventTitle: String,
        data: Map<String, IntemptValue> = emptyMap(),
    ): Boolean =
        // The per-event hot path: init runs once, this runs thousands of times a session. The
        // whole expression is inside the section — wrapping only the left of the elvis would end
        // the section before the `?: false` and misreport the uninitialised case.
        traced("Intempt.track") {
            main("track")?.track(eventTitle, data) ?: false
        }

    /**
     * Associates the current session with [userId], optionally logging [eventTitle] and merging
     * [userAttributes]/[data] onto the user's profile.
     */
    @JvmStatic
    @JvmOverloads
    fun identify(
        userId: String,
        eventTitle: String? = null,
        userAttributes: Map<String, IntemptValue>? = null,
        data: Map<String, IntemptValue>? = null,
    ): Boolean = main("identify")?.identify(userId, eventTitle, userAttributes, data) ?: false

    /**
     * Associates the current session with an account/organization [accountId], optionally logging
     * [eventTitle] and merging [accountAttributes] onto the account's profile.
     */
    @JvmStatic
    @JvmOverloads
    fun group(
        accountId: String,
        eventTitle: String? = null,
        accountAttributes: Map<String, IntemptValue>? = null,
    ): Boolean = main("group")?.group(accountId, eventTitle, accountAttributes) ?: false

    /** Merges the profile previously known as [userId] into [anotherUserId]. */
    @JvmStatic
    fun alias(
        userId: String,
        anotherUserId: String,
    ): Boolean = main("alias")?.alias(userId, anotherUserId) ?: false

    /**
     * Records [eventTitle] and, unlike [track], can attribute it to [userId]/[accountId] and merge
     * [userAttributes]/[accountAttributes] onto their profiles in the same call.
     *
     * The argument order is frozen by the contract. Android used to order it
     * `(eventTitle, accountId, userId, accountAttributes, userAttributes, data)` — identifiers
     * swapped and attributes reversed relative to Swift. Both orders are defensible; having two
     * positional orders that differ only by a swap is what a bridge author discovers in production.
     * `userId` precedes `accountId` here because that is the order every other method uses.
     */
    @Suppress("LongParameterList") // frozen by the contract; see the KDoc above
    @JvmStatic
    @JvmOverloads
    fun record(
        eventTitle: String,
        userId: String? = null,
        accountId: String? = null,
        data: Map<String, IntemptValue>? = null,
        userAttributes: Map<String, IntemptValue>? = null,
        accountAttributes: Map<String, IntemptValue>? = null,
    ): Boolean = main("record")?.record(eventTitle, userId, accountId, data, userAttributes, accountAttributes) ?: false

    /** Records that [quantity] units of [productId] were added to the cart. */
    @JvmStatic
    fun productAdd(
        productId: String,
        quantity: Int,
    ): Boolean = main("productAdd")?.productAdd(productId, quantity) ?: false

    /** Records that [productId] was viewed. */
    @JvmStatic
    fun productView(productId: String): Boolean = main("productView")?.productView(productId) ?: false

    /**
     * Records a completed order.
     *
     * Takes [Product] rather than the `List<Map<String, Any>>` it used to: that shape admitted a
     * map with neither key and failed its validation silently, losing the most valuable event an
     * ecommerce app sends on nothing worse than a misspelled map key.
     */
    @JvmStatic
    fun productOrdered(products: List<Product>): Boolean = main("productOrdered")?.productOrdered(products) ?: false

    /**
     * Records a consent decision for [action], valid until [validUntil] (epoch millis), with
     * optional [email]/[message]/[category] context.
     *
     * Three behaviours here are contractual rather than incidental:
     *
     * - **It transmits even when opted out.** A withdrawal has to reach the server, and the
     *   previous implementation suppressed exactly the call a user who had objected would make.
     * - **[ConsentAction.REJECT] opts out and [ConsentAction.ACCEPT] opts in.** These were
     *   independent settings, so an app could record a rejection and keep collecting.
     * - **It bypasses the event queue**, posting to `/consents/data` outside the `/track` envelope,
     *   and writes a local audit record before the network attempt.
     *
     * [action] is an enum, not a string. A typo in a string consent action is a silent compliance
     * failure, which is the worst class of bug this SDK can have.
     */
    @JvmStatic
    @JvmOverloads
    fun consent(
        action: ConsentAction,
        validUntil: Long,
        email: String? = null,
        message: String? = null,
        category: String? = null,
    ): Boolean = main("consent")?.consent(action, validUntil, email, message, category) ?: false

    // ------------------------------------------------------------- identity and lifecycle

    /** The device-minted anonymous profile id, or "" when the SDK is not running. */
    @JvmStatic
    fun getProfileId(): String = main("getProfileId")?.getProfileId() ?: ""

    /** The current session id, or "" when the SDK is not running or no session has started. */
    @JvmStatic
    fun getSessionId(): String = main("getSessionId")?.getSessionId() ?: ""

    /**
     * Rotates the anonymous identity, **keeping** whatever is queued.
     *
     * Distinct from [reset], and both are required. This exists so the next user of a shared device
     * cannot inherit the previous one's profile; the queued events belong to the user who generated
     * them and are still theirs to send.
     */
    @JvmStatic
    fun logOut() {
        main("logOut")?.logOut()
    }

    /** Rotates the anonymous identity **and discards** queued events. */
    @JvmStatic
    fun reset() {
        main("reset")?.reset()
    }

    // ----------------------------------------------------------------------- opt in / out

    /** Resumes event capture after [optOut]. */
    @JvmStatic
    fun optIn() {
        main("optIn")?.optIn()
    }

    /**
     * Stops event capture **and discards what is already queued.**
     *
     * Setting a flag alone is not enough: events captured before the objection would otherwise sit
     * in the durable queue and upload after it. Queued consent records are deliberately preserved —
     * they are the evidence of the user's decision.
     */
    @JvmStatic
    fun optOut() {
        main("optOut")?.optOut()
    }

    /** True when the user has opted out. False when the SDK is not initialized. */
    @JvmStatic
    fun hasOptedOut(): Boolean = main("hasOptedOut")?.hasOptedOut() ?: false

    /**
     * The inverse of [hasOptedOut]. False when the SDK is not initialized.
     *
     * `isOptedIn`, not `isUserOptIn` — the name the canonical Swift SDK shipped. The contract
     * changed against its own canonical implementation here: three of the five SDKs already spelled
     * it `isOptedIn`, none of the three had published, and a past participle reads as a state where
     * `isUserOptIn` reads as a noun phrase. Swift moves too.
     */
    @JvmStatic
    fun isOptedIn(): Boolean = main("isOptedIn")?.isOptedIn() ?: false

    // --------------------------------------------------------------------------- delivery

    /**
     * Sends whatever is queued now rather than waiting for the timer or the size trigger.
     *
     * [completion] receives the number of events the server accepted, and runs on the delivery
     * worker thread — post to the main thread yourself if it touches UI. It is answered even when
     * nothing was delivered (offline, empty queue, a batch that had to be retried), so awaiting it
     * cannot hang on a failure.
     */
    @JvmStatic
    @JvmOverloads
    fun flush(completion: ((Int) -> Unit)? = null) {
        main("flush")?.flush(completion)
    }

    /**
     * Seconds between automatic flushes; 0 disables the timer, leaving [flush] and the queue's
     * bulk-upload limit as the only triggers. Reads 0 when the SDK is not initialized.
     */
    @JvmStatic
    var flushInterval: Int
        get() = main("flushInterval")?.flushInterval ?: 0
        set(value) {
            main("flushInterval")?.flushInterval = value
        }

    // ------------------------------------------------------------------ errors and options

    /**
     * Called with an [IntemptError] whenever the SDK refuses or fails something.
     *
     * The `Boolean` a capture method returns says *whether*; this says *why*. Without it a `false`
     * from `track()` is equally an opt-out, a reserved event name, a NaN in the attributes and a
     * full disk. Runs on the thread the failure happened on. Pass null to clear.
     */
    @JvmStatic
    fun setErrorListener(listener: ((IntemptError) -> Unit)?) {
        main("setErrorListener")?.setErrorListener(listener)
    }

    /**
     * Lifecycle facts the SDK emits without instrumentation.
     *
     * Defaults are sessions on, version changes off, app-state changes off. The SDK used to emit
     * all three unconditionally, so an app that wanted sessions also got an event on every single
     * foreground/background transition — which is how an event-volume bill surprises someone.
     */
    @JvmStatic
    var automaticEvents: AutomaticEventsOptions
        get() = main("automaticEvents")?.automaticEvents ?: AutomaticEventsOptions()
        set(value) {
            main("automaticEvents")?.automaticEvents = value
        }

    /**
     * UI instrumentation for the `"default"` instance.
     *
     * **Nothing is installed until `start()`.** An SDK may not instrument a host app's view layer
     * as a side effect of being initialised. `initialize()` calls `start()` for you only when
     * `assets/intempt-config.json` sets `isAutoCaptureEnabled` — an explicit request written by the
     * host app rather than a default the SDK assumed.
     *
     * A no-op shim when the SDK is not running, so `Intempt.autocapture.start()` before
     * `initialize()` logs rather than throws.
     */
    @JvmStatic
    val autocapture: AutocaptureFacade = AutocaptureFacade

    /** @see autocapture */
    object AutocaptureFacade {
        /** Sets options without starting. */
        @JvmStatic
        fun configure(options: AutocaptureOptions) {
            main("autocapture.configure")?.autocapture?.configure(options)
        }

        /** Current options, or the defaults when the SDK is not running. */
        @JvmStatic
        fun options(): AutocaptureOptions = main("autocapture.options")?.autocapture?.options ?: AutocaptureOptions()

        /** Installs the hooks. Idempotent; false when already running or the SDK is not. */
        @JvmStatic
        @JvmOverloads
        fun start(options: AutocaptureOptions? = null): Boolean {
            return main("autocapture.start")?.autocapture?.start(options) ?: false
        }

        /** Uninstalls the hooks. Idempotent; false when already stopped or the SDK is not running. */
        @JvmStatic
        fun stop(): Boolean = main("autocapture.stop")?.autocapture?.stop() ?: false

        /** Whether the hooks are installed. False when the SDK is not running. */
        @JvmStatic
        fun isRunning(): Boolean = main("autocapture.isRunning")?.autocapture?.isRunning ?: false
    }

    // ------------------------------------------------------------------------- everything else

    /**
     * Up to [count] product recommendations from feed [feedId], scoped to [productId] when given,
     * returning only [fields]. Null when the SDK is not initialized or the request fails.
     *
     * Named `products` rather than `recommendation` as of 3.0. Same capability, same
     * `/feeds/{feedId}/data` call — the contract picked one name for it across every SDK, because a
     * bridge cannot stay thin while the same request is spelled two ways.
     *
     * Never widen [fields] by omission. [FeedFields.DEFAULT] exists because an unfielded request
     * returns every catalog column including raw ML embedding vectors: 222,919 bytes against 503
     * for the same 10 products.
     */
    @JvmStatic
    @JvmOverloads
    suspend fun products(
        feedId: String,
        count: Int = 10,
        fields: List<String> = FeedFields.DEFAULT,
        productId: String? = null,
    ): JsonObject? = main("products")?.products(feedId, count, fields, productId)

    /**
     * Excludes [view] from autocapture so its text is never recorded.
     *
     * The one sanctioned Android-only method: it takes a native `View` and has no cross-platform
     * meaning, so it is absent from the React Native surface rather than stubbed there.
     */
    @JvmStatic
    fun doNotCaptureText(view: View) {
        main("doNotCaptureText")?.doNotCaptureText(view)
    }

    /**
     * Controls the SDK's own diagnostic logging, separate from event capture.
     *
     * Not part of the cross-SDK contract — logging verbosity is a platform concern — so the
     * start/stop naming is kept rather than renamed to match [optIn]/[optOut], which mean something
     * entirely different.
     */
    object Logging {
        /** Enables the SDK's diagnostic log output. */
        @JvmStatic
        fun start() {
            main("Logging.start")?.startLogging()
        }

        /** Disables the SDK's diagnostic log output. */
        @JvmStatic
        fun stop() {
            main("Logging.stop")?.stopLogging()
        }

        /** False when the SDK is not initialized. */
        @JvmStatic
        fun isLoggingEnabled(): Boolean = main("Logging.isLoggingEnabled")?.isLoggingEnabled() ?: false
    }
}
