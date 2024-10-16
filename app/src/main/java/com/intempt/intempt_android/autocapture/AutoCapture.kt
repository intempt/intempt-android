package com.intempt.intempt_android.autocapture
import android.app.Application
import android.content.Context
import com.intempt.intempt_android.autocapture.screenTracker.ActivityTracker

class AutoCapture(private val context: Context) {
    init{
        registerGlobalActivityLifecycleCallbacks()
    }

    private fun registerGlobalActivityLifecycleCallbacks() {
        val application = context.applicationContext as Application;
        val screenTracker = ActivityTracker()
        application.registerActivityLifecycleCallbacks(screenTracker)
    }


}