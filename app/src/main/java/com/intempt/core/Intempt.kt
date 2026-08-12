package com.intempt.core

import android.content.Context
import android.util.Log
import android.view.View
import com.google.firebase.FirebaseApp
import com.intempt.core.intemptCore.DaggerIntemptCoreComponent
import com.intempt.core.intemptCore.IntemptCoreComponent
import com.intempt.core.intemptCore.IntemptCoreModule
import com.intempt.core.intemptCore.IntemptCoreService
import com.intempt.core.types.DisabledModificationProvider
import com.intempt.core.types.ModificationProvider
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

    /**
     * Null results until a successful [initialize]. Held as a no-op provider rather than a
     * `lateinit`, so reading either of these before initialization returns nothing instead
     * of throwing. See [DisabledModificationProvider].
     */
    var experiment: ModificationProvider = DisabledModificationProvider
        private set

    var personalization: ModificationProvider = DisabledModificationProvider
        private set

    /** True once [initialize] has completed successfully. */
    val isInitialized: Boolean
        get() = intemptCore != null

    /**
     * Starts the SDK. Safe to call more than once; later calls are ignored.
     *
     * @return true when the SDK is running. Previously this returned Unit and swallowed
     *   every failure, so a host app had no way at all to tell a working SDK from a dead
     *   one — the only signal was a printed line on stdout.
     */
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

            val core = component.initService()
            experiment = core.modification.experimentHandler
            personalization = core.modification.personalizationHandler
            intemptCore = core
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

    fun identify(
        userId: String,
        eventTitle: String? = null,
        userAttributes: Map<String, String>? = null,
        data: Map<String, String>? = null,
    ) {
        core("identify")?.capture?.identify(userId, eventTitle, userAttributes, data)
    }

    fun group(
        accountId: String,
        eventTitle: String? = null,
        accountAttributes: Map<String, String>? = null,
    ) {
        core("group")?.capture?.group(accountId, eventTitle, accountAttributes)
    }

    fun track(
        eventTitle: String,
        data: Map<String, String>,
    ) {
        core("track")?.capture?.track(eventTitle, data)
    }

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

    fun alias(
        userId: String,
        anotherUserId: String,
    ) {
        core("alias")?.capture?.alias(userId, anotherUserId)
    }

    fun consent(
        action: String,
        validUntil: Long,
        email: String? = null,
        message: String? = null,
        category: String? = null,
    ) {
        core("consent")?.capture?.consent(action, validUntil, email, message, category)
    }

    fun productAdd(
        productId: String,
        quantity: Int,
    ) {
        core("productAdd")?.capture?.productAdd(productId, quantity)
    }

    fun productOrdered(products: List<Map<String, Any>>) {
        core("productOrdered")?.capture?.productOrdered(products)
    }

    fun productView(productId: String) {
        core("productView")?.capture?.productView(productId)
    }

    suspend fun recommendation(
        id: String,
        quantity: Int,
        fields: List<String>,
        productId: String?,
    ): JsonObject? = core("recommendation")?.capture?.recommendation(id, quantity, fields, productId)

    fun logOut() {
        core("logOut")?.capture?.logOut()
    }

    fun doNotCaptureText(view: View) {
        core("doNotCaptureText")?.capture?.doNotCaptureText(view)
    }

    object Logging {
        fun start() {
            core("Logging.start")?.capture?.enableLogging()
        }

        fun stop() {
            core("Logging.stop")?.capture?.disableLogging()
        }

        /** False when the SDK is not initialized. */
        fun isLoggingEnabled(): Boolean = core("Logging.isLoggingEnabled")?.capture?.isLoggingEnabled() ?: false
    }

    object Tracking {
        fun start() {
            core("Tracking.start")?.capture?.optIn()
        }

        fun stop() {
            core("Tracking.stop")?.capture?.optOut()
        }

        /** False when the SDK is not initialized. */
        fun isTrackingEnabled(): Boolean = core("Tracking.isTrackingEnabled")?.capture?.isTrackingEnabled() ?: false
    }
}
