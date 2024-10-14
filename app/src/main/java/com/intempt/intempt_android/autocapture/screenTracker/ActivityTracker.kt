package com.intempt.intempt_android.autocapture.screenTracker

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.intempt.intempt_android.DispatchEventProps
import com.intempt.intempt_android.EventBus
import com.intempt.intempt_android.Logger
import com.intempt.intempt_android.autocapture.touchTracker.TouchTracker

class ActivityTracker : Application.ActivityLifecycleCallbacks {
    private val touchTracker = TouchTracker();
    private var _fragmentTracker:FragmentTracker? = null;



    override fun onActivityResumed(activity: Activity) {
        touchTracker.registerTouchEventsForActivity(activity)


        EventBus.dispatchEvent(
            DispatchEventProps(
                eventName = "Screen view",
                type = "screen",
                event = null,
                context = activity
            )
        )
        if (activity is AppCompatActivity) {
            _fragmentTracker = FragmentTracker(touchTracker)
            activity.supportFragmentManager.registerFragmentLifecycleCallbacks(_fragmentTracker!!, true)
        }

//        if (activity is AppCompatActivity) {
//            activity.supportFragmentManager.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
//                override fun onFragmentViewCreated(fm: FragmentManager, fragment: Fragment, view: View, savedInstanceState: Bundle?) {
//                    touchTracker.registerTouchEventsForFragment(fragment)
//                }
//            }, true)
//        }

        //EventBus.dispatchEvent("ScreenViewed", activity)
        // Capture touch events for this activity when it's resumed
       //captureTouchEvents(activity)
        Logger.log("AutoCapture | Activity viewed: ${activity.localClassName}")
    }

    override fun onActivityPaused(activity: Activity) {

        EventBus.dispatchEvent(
            DispatchEventProps(
                eventName = "Screen leave",
                type = "screen",
                event = null,
                context = activity
            )
        )
        if (activity is AppCompatActivity) {
            activity.supportFragmentManager.unregisterFragmentLifecycleCallbacks(_fragmentTracker!!)
        }

        Logger.log("AutoCapture | Screen Leave: ${activity.localClassName}")
    }


    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}