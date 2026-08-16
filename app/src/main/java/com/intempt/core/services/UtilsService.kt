package com.intempt.core.services

import android.os.Handler
import kotlinx.coroutines.CancellationException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class UtilsService
    @Inject
    constructor(
        private val logger: LoggerManagerService,
    ) {
        suspend fun <T> withTryCatchSuspend(
            errorMessage: String,
            block: suspend () -> T,
        ): T? {
            return try {
                block()
            } catch (e: CancellationException) {
                // Cancellation is not a failure and must never be swallowed.
                //
                // This guard catches Throwable so that an Error cannot escape into the host
                // app, and CancellationException is a Throwable — so the broad catch silently
                // ate it, logged it as an error, and returned null. Structured concurrency
                // then breaks: the coroutine that was cancelled carries on as though the
                // block merely failed, its parent believes cancellation completed, and a
                // cancelled scope keeps doing work. The symptom is a leak, not an exception.
                //
                // Rethrowing before the general branch keeps both properties: Errors are still
                // contained, cancellation still propagates. Introduced by widening
                // `Exception` to `Throwable` to fix Errors escaping; the widening was right and
                // the missing rethrow was the defect.
                throw e
            } catch (e: Throwable) {
                // Throwable, not Exception. An SDK must never propagate a failure into its
                // host app, and an Error (NoSuchMethodError from a stripped method,
                // OutOfMemoryError from an oversized payload) previously escaped this guard.
                logger.error("$errorMessage; Error: ${e.message}")
                null
            }
        }

        fun <T> withTryCatch(
            errorMessage: String,
            block: () -> T,
        ): T? {
            return try {
                block()
            } catch (e: CancellationException) {
                // Same reasoning as the suspend variant. This one is not itself suspending,
                // but it is called from inside coroutines throughout the SDK, so a
                // CancellationException can still reach it and must still propagate.
                throw e
            } catch (e: Throwable) {
                logger.error("$errorMessage; Error: ${e.message}")
                null
            }
        }

        fun debounce(
            handler: Handler,
            delay: Long,
            runnable: Runnable?,
            action: () -> Unit,
        ): Runnable {
            runnable?.let {
                handler.removeCallbacks(it)
            }
            val newRunnable = Runnable { action() }
            handler.postDelayed(newRunnable, delay)
            return newRunnable
        }

        fun generateId(type: String? = null): String {
            val uuid = UUID.randomUUID().toString()

            return type?.let { it + "_" + uuid } ?: uuid
        }
    }
