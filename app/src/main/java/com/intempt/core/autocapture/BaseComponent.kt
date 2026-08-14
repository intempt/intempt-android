package com.intempt.core.autocapture

import com.intempt.core.services.LoggerManagerService

internal open class BaseComponent(logger: LoggerManagerService? = null) {
    private var isInitialized: Boolean = false

    init {
        isInitialized = true
        val componentName = this::class.simpleName ?: "UnknownComponent"
        logger?.log("$componentName initialized")
    }
}
