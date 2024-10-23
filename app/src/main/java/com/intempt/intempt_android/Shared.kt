package com.intempt.intempt_android

import android.os.Handler
import android.os.Looper
import java.util.UUID


fun generateId(
    type: String? = null
):String{
    val uuid = UUID.randomUUID().toString();

    return type?.let { it + "_" + uuid} ?: uuid
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


fun withTryCatch(
    errorMessage: String,
    block: () -> Unit
) {
    try {
        block()
    } catch (e: Exception) {
        Logger.error("$errorMessage; Error: ${e.message}")
    }
}
