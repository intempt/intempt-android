package com.intempt.core.autocapture

import com.intempt.core.services.LoggerManagerService


open class BaseComponent(logger: LoggerManagerService? = null) {
    private var _isInitialized: Boolean = false;
    init {
        _isInitialized = true;
        val componentName = this::class.simpleName ?: "UnknownComponent"
        logger?.log("$componentName initialized")
    }
}