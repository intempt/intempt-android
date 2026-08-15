package com.intempt.core.services

import com.intempt.core.types.IntemptError
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where an [IntemptError] goes.
 *
 * Exists so [IntemptError] has a caller. A sealed class that nothing constructs and nothing
 * delivers is an export with no consumer — it passes its own tests, reads as finished, and tells a
 * host app nothing. Every refusal path in the capture surface reports through here.
 *
 * The listener runs on **whatever thread the failure happened on** — the caller's thread for a
 * refused `track()`, the delivery worker's for a transport failure. It is documented rather than
 * marshalled to the main thread because posting would reorder errors relative to the calls that
 * caused them, and an error that arrives after the next call is worse than one on an odd thread.
 */
@Singleton
internal class ErrorReporter
    @Inject
    constructor(
        private val logger: LoggerManagerService,
    ) {
        @Volatile
        private var listener: ((IntemptError) -> Unit)? = null

        fun setListener(listener: ((IntemptError) -> Unit)?) {
            this.listener = listener
        }

        /**
         * Reports [error], logging it either way.
         *
         * The log line is not conditional on a listener being set: the overwhelmingly common case
         * is an integrator who has not set one and is reading logcat, and an SDK that goes quiet
         * unless you opt in to hearing from it is how a refused call goes unnoticed for weeks.
         */
        fun report(error: IntemptError) {
            logger.error(error.message)

            // A host app's listener that throws must not propagate: it would surface as an
            // exception from track(), turning "we could not record your event" into a crash in
            // someone's checkout flow. Analytics is not worth a crash.
            try {
                listener?.invoke(error)
            } catch (t: Throwable) {
                logger.error("The error listener threw and was ignored: ${t.message}")
            }
        }
    }
