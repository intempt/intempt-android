package com.intempt.core.autocapture.lifecycleCallbacksTracker
import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import javax.inject.Singleton

@Singleton
internal class LifecycleCallBacksComponent(
     private val srv:LifecycleCallbackService
): Application.ActivityLifecycleCallbacks,
    FragmentManager.FragmentLifecycleCallbacks()
{

    override fun onActivityResumed(activity: Activity) {
        srv.handleScreenView(activity)
        srv.registerTouchEventListener(activity)
        srv.registerChangeEventListener(activity)

        if (activity is AppCompatActivity) {
            activity.supportFragmentManager.registerFragmentLifecycleCallbacks(this, true)
        }
    }

    override fun onActivityPaused(activity: Activity) {
        srv.handleScreenLeave(activity)
        if (activity is AppCompatActivity) {
            activity.supportFragmentManager.unregisterFragmentLifecycleCallbacks(this)
        }
    }

    override fun onActivityDestroyed(activity: Activity) {}


    override fun onFragmentResumed(fm: FragmentManager, fragment: Fragment) {
        srv.handleFragmentVisibility(fragment)
        srv.registerChangeEventListener(fragment.requireActivity())
        srv.registerTouchEventListener(fragment.requireActivity())
    }

    override fun onFragmentAttached(fm: FragmentManager, fragment: Fragment, context: Context) {
        srv.handleFragmentAdd(fragment)
    }

    override fun onFragmentDetached(fm: FragmentManager, fragment: Fragment) {
        srv.handleFragmentRemove(fragment)
    }

    override fun onFragmentPaused(fm: FragmentManager, fragment: Fragment) {
        srv.handleFragmentRemove(fragment)
    }

    override fun onFragmentStopped(fm: FragmentManager, fragment: Fragment) {
        srv.handleFragmentRemove(fragment)
    }


    override fun onFragmentViewCreated(fm: FragmentManager, fragment: Fragment, view: View, savedInstanceState: Bundle?) {
    }
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

}