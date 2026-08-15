package com.intempt.core

import android.content.Context
import android.util.Log
import android.view.View
import com.google.firebase.FirebaseApp
import com.intempt.core.intemptCore.DaggerIntemptCoreComponent
import com.intempt.core.intemptCore.IntemptCoreComponent
import com.intempt.core.intemptCore.IntemptCoreModule
import com.intempt.core.intemptCore.IntemptCoreService
import com.intempt.core.types.ConsentAction
import com.intempt.core.types.IntemptCredentials
import com.intempt.core.types.IntemptValue
import com.intempt.core.types.Product
import kotlinx.serialization.json.JsonObject

/**
 * The SDK's whole public surface.
 *
 * Shaped by the cross-SDK API contract in `intempt-swift@docs/SDK-API-CONTRACT.md`, which every
 * Intempt client SDK conforms to so that a React Native or Flutter bridge is thin and the same
 * call means the same thing on both platforms. 3.0 is a clean break to reach it; there are no
 * deprecated aliases because there are no Android integrations to keep working.
 *
 * Three contract properties are worth stating here because they change how you call this:
 *
 * - **Every capture method returns `Boolean`** — was the event accepted into the queue. It is not
 *   a delivery receipt. False means it will never be sent. These used to return `Unit`, so a
 *   refused call and a working one were indistinguishable.
 * - **Attribute maps are typed.** [IntemptValue] instead of `String`, so `42` and `"42"` stay
 *   different all the way to the platform. Use [IntemptValue.mapOf] to wrap a plain map.
 * - **Every entry point is safe to call when the SDK is not running.** Each one used to
 *   dereference a `lateinit` field, so a failed or absent `initialize()` turned the first
 *   `track()` into `UninitializedPropertyAccessException` inside the host app. Analytics is not
 *   worth a crash; a call made while disabled logs once and does nothing.
 */
object Intempt {
    private const val TAG = "Intempt"

    private lateinit var component: IntemptCoreComponent

    // Nullable rather than lateinit: `null` is a state the facade can check, whereas an
    // unassigned lateinit can only be discovered by throwing.
    @Volatile
    private var intemptCore: IntemptCoreService? = null

    /** True once [initialize] has completed successfully. */
    @JvmStatic
    val isInitialized: Boolean
        get() = intemptCore != null

    /**
     * Starts the SDK, reading credentials from `assets/intempt-config.json`.
     *
     * @return true when the SDK is running.
     */
    @JvmStatic
    fun initialize(context: Context): Boolean = initialize(context, null)

    /**
     * Starts the SDK with credentials supplied at runtime.
     *
     * The asset file remains supported and is still the documented path for a plain Android app,
     * but it must not be the only way in: a React Native or Flutter bridge receives credentials
     * from JavaScript and cannot ship native asset files on its users' behalf, and a white-label
     * app resolves a different project per tenant. [credentials] wins per field when supplied;
     * the file is the fallback.
     *
     * @return true when the SDK is running. False, never an exception, on bad credentials —
     *   read [IntemptCredentials.problems] first if you want to know what is wrong before calling.
     */
    @JvmStatic
    fun initialize(
        context: Context,
        credentials: IntemptCredentials?,
    ): Boolean {
        if (isInitialized) {
            Log.i(TAG, "Already initialized; ignoring this call")
            return true
        }

        if (credentials != null) {
            val problems = credentials.problems()
            if (problems.isNotEmpty()) {
                Log.e(TAG, "Initialization failed: ${problems.joinToString("; ")}. The SDK is disabled.")
                return false
            }
        }

        try {
            component =
                DaggerIntemptCoreComponent.factory()
                    .create(IntemptCoreModule(context, credentials))

            component.inject(this)

            // Credentials are read lazily, so Dagger wires up perfectly against a config asset
            // that does not exist. Before this check, initialize() returned true for an app with
            // no intempt-config.json at all: the SDK reported itself healthy, queued events, and
            // posted them with no Authorization header, so every batch 401'd and was dropped.
            //
            // The whole reason this function returns a Boolean is that it used to return Unit and
            // a host app had no way to tell a working SDK from a dead one. Returning true here
            // made that signal a lie.
            val config = component.config()
            if (!config.isConfigured) {
                Log.e(
                    TAG,
                    "Initialization failed: no ${config.missingCredentials().joinToString(", ")}. " +
                        "Pass IntemptCredentials to initialize(), or add intempt-config.json to " +
                        "src/main/assets. The SDK is disabled and all calls are no-ops.",
                )
                return false
            }

            intemptCore = component.initService()
        } catch (e: Throwable) {
            // Throwable, not Exception. An analytics SDK must never take the host app down,
            // and the failures that actually do are Errors rather than Exceptions: a
            // NoClassDefFoundError from a dependency that is invalid on this API level went
            // straight through `catch (e: Exception)` and killed the app on launch.
            Log.e(TAG, "Initialization failed; the SDK is disabled and all calls are no-ops", e)
            return false
        }

        // Push notifications are OPTIONAL. They only work when the host app has configured
        // Firebase (google-services plugin + google-services.json). If it hasn't, skip
        // gracefully — never fail core analytics init over a missing push setup.
        try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(context)
            }
        } catch (e: Exception) {
            Log.i("FCM", "Firebase not configured; push notifications are disabled.")
        }

        return true
    }

    /**
     * The single guard every entry point goes through. Returns null and logs when the SDK
     * is not running, so callers become no-ops instead of throwing.
     */
    private fun core(caller: String): IntemptCoreService? {
        val core = intemptCore
        if (core == null) {
            Log.w(TAG, "$caller ignored: the SDK is not initialized. Call Intempt.initialize(context) first.")
        }
        return core
    }

    // ------------------------------------------------------------------------ capture

    /** Records a custom event named [eventTitle] with the given [data] properties. */
    @JvmStatic
    @JvmOverloads
    fun track(
        eventTitle: String,
        data: Map<String, IntemptValue> = emptyMap(),
    ): Boolean = core("track")?.capture?.track(eventTitle, data) ?: false

    /**
     * Associates the current session with [userId], optionally logging [eventTitle] and
     * merging [userAttributes]/[data] onto the user's profile.
     */
    @JvmStatic
    @JvmOverloads
    fun identify(
        userId: String,
        eventTitle: String? = null,
        userAttributes: Map<String, IntemptValue>? = null,
        data: Map<String, IntemptValue>? = null,
    ): Boolean = core("identify")?.capture?.identify(userId, eventTitle, userAttributes, data) ?: false

    /**
     * Associates the current session with an account/organization [accountId], optionally
     * logging [eventTitle] and merging [accountAttributes] onto the account's profile.
     */
    @JvmStatic
    @JvmOverloads
    fun group(
        accountId: String,
        eventTitle: String? = null,
        accountAttributes: Map<String, IntemptValue>? = null,
    ): Boolean = core("group")?.capture?.group(accountId, eventTitle, accountAttributes) ?: false

    /** Merges the profile previously known as [userId] into [anotherUserId]. */
    @JvmStatic
    fun alias(
        userId: String,
        anotherUserId: String,
    ): Boolean = core("alias")?.capture?.alias(userId, anotherUserId) ?: false

    /**
     * Records [eventTitle] and, unlike [track], can attribute it to [userId]/[accountId] and
     * merge [userAttributes]/[accountAttributes] onto their profiles in the same call.
     *
     * The argument order is frozen by the contract. Android used to order it
     * `(eventTitle, accountId, userId, accountAttributes, userAttributes, data)` — identifiers
     * swapped and attributes reversed relative to Swift. Both orders are defensible; having two
     * is not, and two positional orders that differ only by a swap is the kind of divergence a
     * bridge author discovers in production. `userId` precedes `accountId` here because that is
     * the order every other method on this object uses.
     */
    @JvmStatic
    @JvmOverloads
    fun record(
        eventTitle: String,
        userId: String? = null,
        accountId: String? = null,
        data: Map<String, IntemptValue>? = null,
        userAttributes: Map<String, IntemptValue>? = null,
        accountAttributes: Map<String, IntemptValue>? = null,
    ): Boolean =
        core("record")?.capture?.record(
            eventTitle,
            userId,
            accountId,
            data,
            userAttributes,
            accountAttributes,
        ) ?: false

    /** Records that [quantity] units of [productId] were added to the cart. */
    @JvmStatic
    fun productAdd(
        productId: String,
        quantity: Int,
    ): Boolean = core("productAdd")?.capture?.productAdd(productId, quantity) ?: false

    /** Records that [productId] was viewed. */
    @JvmStatic
    fun productView(productId: String): Boolean = core("productView")?.capture?.productView(productId) ?: false

    /**
     * Records a completed order.
     *
     * Takes [Product] rather than the `List<Map<String, Any>>` it used to: that shape admitted a
     * map with neither key and failed its validation silently, losing the most valuable event an
     * ecommerce app sends on nothing worse than a misspelled map key.
     */
    @JvmStatic
    fun productOrdered(products: List<Product>): Boolean = core("productOrdered")?.capture?.productOrdered(products) ?: false

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
     * - **It bypasses the event queue**, posting to `/consents/data` outside the `/track`
     *   envelope, and writes a local audit record before the network attempt.
     *
     * [action] is an enum, not a string. A typo in a string consent action is a silent
     * compliance failure, which is the worst class of bug this SDK can have.
     */
    @JvmStatic
    @JvmOverloads
    fun consent(
        action: ConsentAction,
        validUntil: Long,
        email: String? = null,
        message: String? = null,
        category: String? = null,
    ): Boolean = core("consent")?.capture?.consent(action, validUntil, email, message, category) ?: false

    // ------------------------------------------------------------- identity and lifecycle

    /** The device-minted anonymous profile id, or "" when the SDK is not running. */
    @JvmStatic
    fun getProfileId(): String = core("getProfileId")?.capture?.getProfileId() ?: ""

    /** The current session id, or "" when the SDK is not running or no session has started. */
    @JvmStatic
    fun getSessionId(): String = core("getSessionId")?.capture?.getSessionId() ?: ""

    /**
     * Rotates the anonymous identity, **keeping** whatever is queued.
     *
     * Distinct from [reset], and both are required. This exists so the next user of a shared
     * device cannot inherit the previous one's profile; the queued events belong to the user who
     * generated them and are still theirs to send.
     */
    @JvmStatic
    fun logOut() {
        core("logOut")?.capture?.logOut()
    }

    /** Rotates the anonymous identity **and discards** queued events. */
    @JvmStatic
    fun reset() {
        core("reset")?.capture?.reset()
    }

    // ----------------------------------------------------------------------- opt in / out

    /** Resumes event capture after [optOut]. */
    @JvmStatic
    fun optIn() {
        core("optIn")?.capture?.optIn()
    }

    /**
     * Stops event capture **and discards what is already queued.**
     *
     * Setting a flag alone is not enough: events captured before the objection would sit in the
     * durable queue and upload after it. Queued consent records are deliberately preserved —
     * they are the evidence of the user's decision.
     */
    @JvmStatic
    fun optOut() {
        core("optOut")?.capture?.optOut()
    }

    /** True when the user has opted out. False when the SDK is not initialized. */
    @JvmStatic
    fun hasOptedOut(): Boolean = core("hasOptedOut")?.capture?.hasOptedOut() ?: false

    /**
     * The inverse of [hasOptedOut]. False when the SDK is not initialized.
     *
     * `isOptedIn`, not `isUserOptIn` — the name the canonical Swift SDK shipped. The contract
     * changed against its own canonical implementation here: three of the five SDKs already
     * spelled it `isOptedIn`, none of the three had published, and a past participle reads as a
     * state where `isUserOptIn` reads as a noun phrase. Swift moves too.
     */
    @JvmStatic
    fun isOptedIn(): Boolean = core("isOptedIn")?.capture?.isOptedIn() ?: false

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
        core("flush")?.capture?.flush(completion)
    }

    /**
     * Seconds between automatic flushes; 0 disables the timer, leaving [flush] and the queue's
     * bulk-upload limit as the only triggers. Reads 0 when the SDK is not initialized.
     */
    @JvmStatic
    var flushInterval: Int
        get() = core("flushInterval")?.capture?.flushInterval ?: 0
        set(value) {
            core("flushInterval")?.capture?.let { it.flushInterval = value }
        }

    // ------------------------------------------------------------------------- everything else

    /**
     * Fetches up to [quantity] product recommendations for recommender [id], scoped to
     * [productId] when given, returning only the requested [fields]. Returns null when the
     * SDK is not initialized or the request fails.
     *
     * Never widen [fields] by omission. An unfielded request returns every catalog column
     * including raw ML embedding vectors — 222,919 bytes against 503 for the same 10 products.
     */
    @JvmStatic
    suspend fun recommendation(
        id: String,
        quantity: Int,
        fields: List<String>,
        productId: String?,
    ): JsonObject? = core("recommendation")?.capture?.recommendation(id, quantity, fields, productId)

    /**
     * Excludes [view] from autocapture so its text is never recorded.
     *
     * The one sanctioned Android-only method: it takes a native `View` and has no cross-platform
     * meaning, so it is absent from the React Native surface rather than stubbed there.
     */
    @JvmStatic
    fun doNotCaptureText(view: View) {
        core("doNotCaptureText")?.capture?.doNotCaptureText(view)
    }

    /**
     * Controls the SDK's own diagnostic logging, separate from event capture.
     *
     * Not part of the cross-SDK contract — logging verbosity is a platform concern — so the
     * start/stop naming is kept rather than renamed to match [optIn]/[optOut], which mean
     * something entirely different.
     */
    object Logging {
        /** Enables the SDK's diagnostic log output. */
        @JvmStatic
        fun start() {
            core("Logging.start")?.capture?.enableLogging()
        }

        /** Disables the SDK's diagnostic log output. */
        @JvmStatic
        fun stop() {
            core("Logging.stop")?.capture?.disableLogging()
        }

        /** False when the SDK is not initialized. */
        @JvmStatic
        fun isLoggingEnabled(): Boolean = core("Logging.isLoggingEnabled")?.capture?.isLoggingEnabled() ?: false
    }
}
