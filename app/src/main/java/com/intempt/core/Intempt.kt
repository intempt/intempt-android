@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core

import android.content.Context
import android.util.Log
import android.view.View
import com.intempt.core.intemptCore.DaggerIntemptCoreComponent
import com.intempt.core.intemptCore.IntemptCoreComponent
import com.intempt.core.intemptCore.IntemptCoreModule
import com.intempt.core.intemptCore.IntemptCoreService
import com.intempt.core.internal.PushBridge
import kotlinx.serialization.json.JsonObject

/**
 * The SDK's whole public surface.
 *
 * Every entry point below is safe to call when the SDK is not running. Previously each one
 * dereferenced a `lateinit` field, so if `initialize()` had failed — or had simply not been
 * called — the first `track()` threw `UninitializedPropertyAccessException` into the host
 * app. Initialization catches everything and disables itself, which meant the SDK survived
 * its own failure and then crashed its host on the next call. Analytics is not worth a
 * crash, so a call made while disabled logs once and does nothing.
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
     * Starts the SDK. Safe to call more than once; later calls are ignored.
     *
     * @return true when the SDK is running. Previously this returned Unit and swallowed
     *   every failure, so a host app had no way at all to tell a working SDK from a dead
     *   one — the only signal was a printed line on stdout.
     */
    @JvmStatic
    fun initialize(context: Context): Boolean {
        if (isInitialized) {
            Log.i(TAG, "Already initialized; ignoring this call")
            return true
        }

        try {
            component =
                DaggerIntemptCoreComponent.factory()
                    .create(IntemptCoreModule(context))

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
                    "Initialization failed: intempt-config.json is missing or incomplete " +
                        "(no ${config.missingCredentials().joinToString(", ")}). The SDK is " +
                        "disabled and all calls are no-ops. Add the file to src/main/assets.",
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

        // Push notifications are OPTIONAL and live in the separate :push module. This is a
        // presence-gated reflection call rather than a direct dependency — :app cannot depend on
        // :push (the dependency direction is :push -> :app) — so it degrades silently to a no-op
        // when the host app hasn't added :push, and also when it has but hasn't configured
        // Firebase (google-services plugin + google-services.json). Never fail core analytics
        // init over a missing or absent push setup.
        PushBridge.initializeIfPresent(context)

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

    /**
     * Associates the current session with [userId], optionally logging [eventTitle] and
     * merging [userAttributes]/[data] onto the user's profile.
     */
    @JvmStatic
    @JvmOverloads
    fun identify(
        userId: String,
        eventTitle: String? = null,
        userAttributes: Map<String, String>? = null,
        data: Map<String, String>? = null,
    ) {
        core("identify")?.capture?.identify(userId, eventTitle, userAttributes, data)
    }

    /**
     * Associates the current session with an account/organization [accountId], optionally
     * logging [eventTitle] and merging [accountAttributes] onto the account's profile.
     */
    @JvmStatic
    @JvmOverloads
    fun group(
        accountId: String,
        eventTitle: String? = null,
        accountAttributes: Map<String, String>? = null,
    ) {
        core("group")?.capture?.group(accountId, eventTitle, accountAttributes)
    }

    /** Records a custom event named [eventTitle] with the given [data] properties. */
    @JvmStatic
    fun track(
        eventTitle: String,
        data: Map<String, String>,
    ) {
        core("track")?.capture?.track(eventTitle, data)
    }

    /**
     * Records [eventTitle] and, unlike [track], can attribute it to [accountId]/[userId] and
     * merge [accountAttributes]/[userAttributes] onto their profiles in the same call.
     */
    @JvmStatic
    @JvmOverloads
    fun record(
        eventTitle: String,
        accountId: String? = null,
        userId: String? = null,
        accountAttributes: Map<String, String>? = null,
        userAttributes: Map<String, String>? = null,
        data: Map<String, String>? = null,
    ) {
        core("record")?.capture?.record(
            eventTitle,
            accountId,
            userId,
            accountAttributes,
            userAttributes,
            data,
        )
    }

    /** Merges the profile previously known as [userId] into [anotherUserId]. */
    @JvmStatic
    fun alias(
        userId: String,
        anotherUserId: String,
    ) {
        core("alias")?.capture?.alias(userId, anotherUserId)
    }

    /**
     * Records a consent decision (e.g. "opt-in"/"opt-out") for [action], valid until
     * [validUntil] (epoch millis), with optional [email]/[message]/[category] context.
     */
    @JvmStatic
    @JvmOverloads
    fun consent(
        action: String,
        validUntil: Long,
        email: String? = null,
        message: String? = null,
        category: String? = null,
    ) {
        core("consent")?.capture?.consent(action, validUntil, email, message, category)
    }

    /** Records that [quantity] units of [productId] were added to the cart. */
    @JvmStatic
    fun productAdd(
        productId: String,
        quantity: Int,
    ) {
        core("productAdd")?.capture?.productAdd(productId, quantity)
    }

    /** Records an order completion for [products], each a map of that product's properties. */
    @JvmStatic
    fun productOrdered(products: List<Map<String, Any>>) {
        core("productOrdered")?.capture?.productOrdered(products)
    }

    /** Records that [productId] was viewed. */
    @JvmStatic
    fun productView(productId: String) {
        core("productView")?.capture?.productView(productId)
    }

    /**
     * Fetches up to [quantity] product recommendations for recommender [id], scoped to
     * [productId] when given, returning only the requested [fields]. Returns null when the
     * SDK is not initialized or the request fails.
     */
    @JvmStatic
    suspend fun recommendation(
        id: String,
        quantity: Int,
        fields: List<String>,
        productId: String?,
    ): JsonObject? = core("recommendation")?.capture?.recommendation(id, quantity, fields, productId)

    /** Clears the identified user/account for the current session. */
    @JvmStatic
    fun logOut() {
        core("logOut")?.capture?.logOut()
    }

    /** Excludes [view] from autocapture so its text is never recorded. */
    @JvmStatic
    fun doNotCaptureText(view: View) {
        core("doNotCaptureText")?.capture?.doNotCaptureText(view)
    }

    /** Controls the SDK's own diagnostic logging, separate from event tracking. */
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

    /** Controls whether events are captured at all (independent of [Logging]). */
    object Tracking {
        /** Opts back in to event capture after [stop]. */
        @JvmStatic
        fun start() {
            core("Tracking.start")?.capture?.optIn()
        }

        /** Opts out of event capture; calls are accepted but nothing is recorded or sent. */
        @JvmStatic
        fun stop() {
            core("Tracking.stop")?.capture?.optOut()
        }

        /** False when the SDK is not initialized. */
        @JvmStatic
        fun isTrackingEnabled(): Boolean = core("Tracking.isTrackingEnabled")?.capture?.isTrackingEnabled() ?: false
    }
}
