package com.intempt.core.types

import android.app.Activity

// For activity lifecycle events
internal interface ActivityLifecycleListener {
    fun onActivityResumed(activity: Activity)

    fun onActivityPaused(activity: Activity)
}
