package com.intempt.core.autocapture
import com.intempt.core.services.Logger


open class BaseComponent() {
    var isInitialized: Boolean = false;



    init {
        isInitialized = true;

        val componentName = this::class.simpleName ?: "UnknownComponent"
        Logger.log("$componentName initialized")
    }
}