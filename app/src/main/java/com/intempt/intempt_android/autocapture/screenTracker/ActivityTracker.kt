package com.intempt.intempt_android.autocapture.screenTracker

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.intempt.intempt_android.DispatchEventProps
import com.intempt.intempt_android.EventBus
import com.intempt.intempt_android.Logger
import com.intempt.intempt_android.StorageHandler
import com.intempt.intempt_android.autocapture.touchTracker.TouchTracker

class ActivityTracker : Application.ActivityLifecycleCallbacks {
    private val touchTracker = TouchTracker();
    private var _fragmentTracker:FragmentTracker? = null;



    override fun onActivityResumed(activity: Activity) {
        Logger.log("AutoCapture | Activity viewed: ${activity.localClassName}")
        touchTracker.registerTouchEventsForActivity(activity)

        StorageHandler.pageIdSet()

        EventBus.dispatchEvent(
            DispatchEventProps(
                eventName = "Screen view",
                entityName="screenView",
                type = "screen",
                event = null,
                context = activity
            )
        )
        if (activity is AppCompatActivity) {
            _fragmentTracker = FragmentTracker(touchTracker)
            activity.supportFragmentManager.registerFragmentLifecycleCallbacks(_fragmentTracker!!, true)
        }


    }

    override fun onActivityPaused(activity: Activity) {
        Logger.log("AutoCapture | Screen Leave: ${activity.localClassName}")
        EventBus.dispatchEvent(
            DispatchEventProps(
                eventName = "Screen leave",
                entityName="screenLeave",
                type = "screen",
                event = null,
                context = activity
            )
        )
        if (activity is AppCompatActivity) {
            activity.supportFragmentManager.unregisterFragmentLifecycleCallbacks(_fragmentTracker!!)
        }


    }


    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}