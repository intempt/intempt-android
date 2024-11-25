package com.intempt.core.services

import android.os.Handler
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
internal class UtilsService @Inject constructor(
    private val logger: LoggerManagerService
){

    suspend fun <T> withTryCatchSuspend(errorMessage: String, block: suspend () -> T): T?  {
       return try {
            block()
        } catch (e: Exception) {
            logger.error("$errorMessage; Error: ${e.message}")
            null
       }
    }

    fun <T> withTryCatch(errorMessage: String, block: () -> T): T? {
        return try {
             block()
        } catch (e: Exception) {
            logger.error("$errorMessage; Error: ${e.message}")
            null
        }
    }

    fun debounce(
        handler: Handler,
        delay: Long,
        runnable: Runnable?,
        action: () -> Unit
    ): Runnable {
        runnable?.let {
            handler.removeCallbacks(it)
        }
        val newRunnable = Runnable { action() }
        handler.postDelayed(newRunnable, delay)
        return newRunnable
    }

    fun generateId(
        type: String? = null
    ):String{
        val uuid = UUID.randomUUID().toString();

        return type?.let { it + "_" + uuid} ?: uuid
    }
}