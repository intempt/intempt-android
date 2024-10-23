package com.intempt.intempt_android.autocapture

import com.intempt.intempt_android.Logger

open class BaseComponent {
    init {
        val moduleName = javaClass.enclosingClass?.simpleName ?: "UnknownModule"
        val componentName = this::class.simpleName ?: "UnknownComponent"
        Logger.log("$moduleName | $componentName initialized")
    }
}