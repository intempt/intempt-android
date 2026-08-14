package com.intempt.core.services

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The SDK's single logging path.
 *
 * There used to be three. This service, a separate [com.intempt.core.queue.QueueLog] inside the
 * vendored queue package with its own boolean, and raw `android.util.Log` calls scattered across the
 * Firebase and notification classes. All three wrote to logcat under the same tag, which is what
 * hid the problem: the output looked unified while the switch was not. Turning logging off left the
 * raw calls printing, and the queue's own flag was set from a different place, so "is logging on?"
 * had three answers.
 *
 * Everything now routes here, and [ConfigManagerService.isLoggingEnabled] is the only switch.
 *
 * ## Levels
 *
 * Mixpanel's SDK exposes VERBOSE→NONE through a single `MPLog` wrapper and uses it consistently.
 * The five methods below are the same shape. [log] and [error] are kept as aliases for [info] and
 * [error] because 40-odd existing call sites use them and renaming those would bury a plumbing
 * change in a rename diff.
 *
 * ## Two deliberate exceptions
 *
 * Two places still call `android.util.Log` directly, and both would be actively worse routed
 * through here:
 *
 *  - [com.intempt.core.Intempt.initialize] reports that initialization failed. That message exists
 *    to tell a customer the SDK is dead, and gating it behind a logging flag that defaults to off
 *    means the one line they need is the one line they never see.
 *  - [ConfigManagerService] reports a malformed `apiKey`. It cannot use this service: this service
 *    depends on it, so the wiring is circular — and more to the point, a failure to read the config
 *    is a failure to know whether logging is enabled.
 *
 * Both are error-level and fire at most once per process.
 */
@Singleton
internal class LoggerManagerService
    @Inject
    constructor(
        private val config: ConfigManagerService,
    ) {
        /**
         * Installs this instance as the sink for the vendored queue package.
         *
         * Called from the queue's own configuration path rather than from here, so that a
         * LoggerManagerService constructed for a test does not silently take over the static sink
         * for every other test in the JVM.
         */
        fun asQueueSink(): (Int, String, String, Throwable?) -> Unit =
            { priority, tag, message, throwable ->
                if (config.isLoggingEnabled) {
                    if (throwable == null) {
                        Log.println(priority, tag, message)
                    } else {
                        Log.println(priority, tag, message + '\n' + Log.getStackTraceString(throwable))
                    }
                }
            }

        fun verbose(message: String) {
            if (!config.isLoggingEnabled) return
            Log.v(TAG, message)
        }

        fun debug(message: String) {
            if (!config.isLoggingEnabled) return
            Log.d(TAG, message)
        }

        fun info(message: String) {
            if (!config.isLoggingEnabled) return
            Log.i(TAG, message)
        }

        fun warn(message: String) {
            if (!config.isLoggingEnabled) return
            Log.w(TAG, message)
        }

        fun error(message: String) {
            if (!config.isLoggingEnabled) return
            Log.e(TAG, message)
        }

        fun error(
            message: String,
            throwable: Throwable?,
        ) {
            if (!config.isLoggingEnabled) return
            Log.e(TAG, message, throwable)
        }

        /** Alias for [info]. Kept because the existing call sites use it. */
        fun log(message: String) = info(message)

        internal companion object {
            /**
             * One tag for the whole SDK, so a customer can filter on it. Changing it is a breaking
             * change for anyone with a logcat filter, which is why it is a constant rather than
             * repeated at 40 call sites.
             */
            const val TAG = "Intempt"
        }
    }
