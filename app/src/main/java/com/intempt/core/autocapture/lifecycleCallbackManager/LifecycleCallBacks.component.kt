package com.intempt.core.autocapture.lifecycleCallbackManager
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
class LifecycleCallBacksComponent(
    private val activityLifecycleListeners: List<ActivityLifecycleListener>,
    private val fragmentLifecycleListeners: List<FragmentLifecycleListener>
): Application.ActivityLifecycleCallbacks, FragmentManager.FragmentLifecycleCallbacks() {

    override fun onActivityResumed(activity: Activity) {
        for (listener in activityLifecycleListeners) {
            listener.onActivityResumed(activity)
        }
        if (activity is AppCompatActivity) {
            activity.supportFragmentManager.registerFragmentLifecycleCallbacks(this, true)
        }
    }

    override fun onActivityPaused(activity: Activity) {
        for (listener in activityLifecycleListeners) {
            listener.onActivityPaused(activity)
        }
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
    }

    override fun onFragmentResumed(fm: FragmentManager, fragment: Fragment) {
        super.onFragmentResumed(fm, fragment);
    }

    override fun onFragmentDetached(fm: FragmentManager, fragment: Fragment) {
        super.onFragmentDetached(fm, fragment);
    }

    override fun onFragmentPaused(fm: FragmentManager, fragment: Fragment) {
        super.onFragmentPaused(fm, fragment)
    }

    override fun onFragmentViewCreated(fm: FragmentManager, fragment: Fragment, view: View, savedInstanceState: Bundle?) {
        for (listener in fragmentLifecycleListeners) {
            listener.onFragmentViewCreated(fm, fragment, view, savedInstanceState)
        }
    }

}