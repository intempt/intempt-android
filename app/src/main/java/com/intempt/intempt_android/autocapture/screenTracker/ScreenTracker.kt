package com.intempt.intempt_android.autocapture.screenTracker

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.intempt.intempt_android.types.DispatchEventProps
import com.intempt.intempt_android.Logger
import com.intempt.intempt_android.StorageHandler
import com.intempt.intempt_android.autocapture.changeTracker.ChangeTracker
import com.intempt.intempt_android.autocapture.touchTracker.TouchTracker
import com.intempt.intempt_android.eventPool.EventPool
import javax.inject.Inject

class ScreenTracker @Inject constructor(
    private val touchTracker: TouchTracker,
    private val changeTracker: ChangeTracker,
    private val eventSrv: EventPool
): Application.ActivityLifecycleCallbacks,
    FragmentManager.FragmentLifecycleCallbacks() {
    override fun onActivityResumed(activity: Activity) {
        Logger.log("AutoCapture | Activity viewed: ${activity.localClassName}")
        touchTracker.registerForActivity(activity)
        changeTracker.registerForActivity(activity)

        StorageHandler.pageIdSet()

        eventSrv.dispatchEvent(
            DispatchEventProps(
                eventName = "Screen view",
                entityName="screenView",
                type = "screen",
                event = null,
                context = activity
            )
        )
        if (activity is AppCompatActivity) {
            activity.supportFragmentManager.registerFragmentLifecycleCallbacks(this, true)
        }


    }

    override fun onActivityPaused(activity: Activity) {
        Logger.log("AutoCapture | Screen Leave: ${activity.localClassName}")

        eventSrv.dispatchEvent(
            DispatchEventProps(
                eventName = "Screen leave",
                entityName="screenLeave",
                type = "screen",
                event = null,
                context = activity
            )
        )
        if (activity is AppCompatActivity) {
            activity.supportFragmentManager.unregisterFragmentLifecycleCallbacks(this)
        }


    }


    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}

    override fun onFragmentAttached(fm: FragmentManager, fragment: Fragment, context: Context) {
        super.onFragmentAttached(fm, fragment, context);

        val key = "addedFragment"
        StorageHandler.saveFragmentName(
            key,
            fragment,
        )
    }

    override fun onFragmentResumed(fm: FragmentManager, fragment: Fragment) {
        super.onFragmentResumed(fm, fragment);

        val key = "visibleFragment"
        StorageHandler.saveFragmentName(
            key,
            fragment,
        )

        eventSrv.dispatchEvent(
            DispatchEventProps(
                eventName = "Fragment transition",
                entityName="fragmentTransition",
                type = "fragment",
                event = null,
                context = fragment.requireActivity()
            )
        )

        Logger.log("AutoCapture | onFragmentResumed: ${fragment::class.java.simpleName}")
    }

    override fun onFragmentDetached(fm: FragmentManager, fragment: Fragment) {
        super.onFragmentDetached(fm, fragment);

        val key = "removedFragment";
        StorageHandler.saveFragmentName(
            key,
            fragment,
        )
        Logger.log("AutoCapture | onFragmentDetached: ${fragment::class.java.simpleName}")
    }

    override fun onFragmentPaused(fm: FragmentManager, fragment: Fragment) {
        super.onFragmentPaused(fm, fragment)

        Logger.log("AutoCapture | onFragmentPaused: ${fragment::class.java.simpleName}")
    }

    override fun onFragmentViewCreated(fm: FragmentManager, fragment: Fragment, view: View, savedInstanceState: Bundle?) {
        touchTracker.registerForFragment(fragment)
        changeTracker.registerForFragment(fragment)
    }
}