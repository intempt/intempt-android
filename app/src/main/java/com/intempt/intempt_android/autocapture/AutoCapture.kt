package com.intempt.intempt_android.autocapture

import android.app.Activity
import android.app.Application
import android.content.Context
import android.view.MotionEvent
import android.view.Window
import com.intempt.intempt_android.DispatchEventProps
import com.intempt.intempt_android.EventBus
import com.intempt.intempt_android.Logger
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


    private fun captureTouchEvents(activity: Activity) {
        activity.window.callback = object : Window.Callback by activity.window.callback {
            override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
                Logger.log("AutoCapture | TouchEvents: ${activity.localClassName}")

                EventBus.dispatchEvent(
                    DispatchEventProps(
                        eventName = "TouchEvent",
                        type = "touch",
                        event = null,
                        context = activity
                    )
                )

                return activity.dispatchTouchEvent(event)
            }
        }
    }

}