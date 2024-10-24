package com.intempt.core.autocapture

import com.intempt.core.services.Logger

open class BaseComponent {
    init {
        val moduleName = javaClass.enclosingClass?.simpleName ?: "UnknownModule"
        val componentName = this::class.simpleName ?: "UnknownComponent"
        Logger.log("$moduleName | $componentName initialized")
    }
}