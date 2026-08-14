package com.intempt.core

import android.content.Context
import android.util.Log
import android.view.View
import com.google.firebase.FirebaseApp
import com.intempt.core.intemptCore.DaggerIntemptCoreComponent
import com.intempt.core.intemptCore.IntemptCoreComponent
import com.intempt.core.intemptCore.IntemptCoreModule
import com.intempt.core.intemptCore.IntemptCoreService
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

    @JvmStatic
    @JvmOverloads
    fun group(
        accountId: String,
        eventTitle: String? = null,
        accountAttributes: Map<String, String>? = null,
    ) {
        core("group")?.capture?.group(accountId, eventTitle, accountAttributes)
    }

    @JvmStatic
    fun track(
        eventTitle: String,
        data: Map<String, String>,
    ) {
        core("track")?.capture?.track(eventTitle, data)
    }

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

    @JvmStatic
    fun alias(
        userId: String,
        anotherUserId: String,
    ) {
        core("alias")?.capture?.alias(userId, anotherUserId)
    }

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

    @JvmStatic
    fun productAdd(
        productId: String,
        quantity: Int,
    ) {
        core("productAdd")?.capture?.productAdd(productId, quantity)
    }

    @JvmStatic
    fun productOrdered(products: List<Map<String, Any>>) {
        core("productOrdered")?.capture?.productOrdered(products)
    }

    @JvmStatic
    fun productView(productId: String) {
        core("productView")?.capture?.productView(productId)
    }

    @JvmStatic
    suspend fun recommendation(
        id: String,
        quantity: Int,
        fields: List<String>,
        productId: String?,
    ): JsonObject? = core("recommendation")?.capture?.recommendation(id, quantity, fields, productId)

    @JvmStatic
    fun logOut() {
        core("logOut")?.capture?.logOut()
    }

    @JvmStatic
    fun doNotCaptureText(view: View) {
        core("doNotCaptureText")?.capture?.doNotCaptureText(view)
    }

    object Logging {
        @JvmStatic
        fun start() {
            core("Logging.start")?.capture?.enableLogging()
        }

        @JvmStatic
        fun stop() {
            core("Logging.stop")?.capture?.disableLogging()
        }

        /** False when the SDK is not initialized. */
        @JvmStatic
        fun isLoggingEnabled(): Boolean = core("Logging.isLoggingEnabled")?.capture?.isLoggingEnabled() ?: false
    }

    object Tracking {
        @JvmStatic
        fun start() {
            core("Tracking.start")?.capture?.optIn()
        }

        @JvmStatic
        fun stop() {
            core("Tracking.stop")?.capture?.optOut()
        }

        /** False when the SDK is not initialized. */
        @JvmStatic
        fun isTrackingEnabled(): Boolean = core("Tracking.isTrackingEnabled")?.capture?.isTrackingEnabled() ?: false
    }
}
