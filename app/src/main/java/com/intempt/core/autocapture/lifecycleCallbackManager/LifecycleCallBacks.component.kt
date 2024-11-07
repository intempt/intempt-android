package com.intempt.core.autocapture.lifecycleCallbackManager
import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.intempt.core.types.ActivityLifecycleListener
import com.intempt.core.types.FragmentLifecycleListener
import javax.inject.Singleton

@Singleton
internal class LifecycleCallBacksComponent(
//    private val activityLifecycleListeners: List<ActivityLifecycleListener>,
//    private val fragmentLifecycleListeners: List<FragmentLifecycleListener>
     private val srv:LifecycleCallbackService
): Application.ActivityLifecycleCallbacks, FragmentManager.FragmentLifecycleCallbacks() {

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
       // registerActivityListener(activity)
        srv.handleScreenView(activity)

        if (activity is AppCompatActivity) {
          activity.supportFragmentManager.registerFragmentLifecycleCallbacks(this, true)
        }
    }

    override fun onActivityResumed(activity: Activity) {
       // registerActivityListener(activity)
        srv.handleScreenView(activity)
        if (activity is AppCompatActivity) {
            activity.supportFragmentManager.registerFragmentLifecycleCallbacks(this, true)
        }
    }

    override fun onActivityPaused(activity: Activity) {
        //unregisterActivityListener(activity)
        srv.handleScreenLeave(activity)
        if (activity is AppCompatActivity) {
            activity.supportFragmentManager.unregisterFragmentLifecycleCallbacks(this)
        }
    }



    override fun onFragmentViewCreated(fm: FragmentManager, fragment: Fragment, view: View, savedInstanceState: Bundle?) {
       // registerFragmentViewCreatedListener(fm, fragment, view, savedInstanceState)
        srv.handleFragmentVisibility(fragment)
    }

    override fun onFragmentResumed(fm: FragmentManager, fragment: Fragment) {
        srv.handleFragmentVisibility(fragment)
        // registerFragmentResumeListener(fm, fragment);
    }

    override fun onFragmentAttached(fm: FragmentManager, fragment: Fragment, context: Context) {
       // registerFragmentAttachedListener(fm, fragment, context);

        srv.handleFragmentAdd(fragment)
    }



    override fun onFragmentDetached(fm: FragmentManager, fragment: Fragment) {
        //registerFragmentDetachedListener(fm, fragment);
        srv.handleFragmentRemove(fragment)
    }
    override fun onFragmentPaused(fm: FragmentManager, fragment: Fragment) {
       // registerFragmentDetachedListener(fm, fragment);
        srv.handleFragmentRemove(fragment)
    }
    override fun onFragmentStopped(fm: FragmentManager, fragment: Fragment) {
        //registerFragmentDetachedListener(fm, fragment);
        srv.handleFragmentRemove(fragment)
    }



    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}


    private fun registerFragmentDetachedListener(fm: FragmentManager, fragment: Fragment){
//        for (listener in fragmentLifecycleListeners) {
//            listener.onFragmentDetached(fm, fragment)
//        }
    }

    private fun registerFragmentResumeListener(fm: FragmentManager, fragment: Fragment){
//        for (listener in fragmentLifecycleListeners) {
//            listener.onFragmentResumed(fm, fragment)
//        }
    }

    private fun registerFragmentAttachedListener(fm: FragmentManager, fragment: Fragment, context: Context){
//        for (listener in fragmentLifecycleListeners) {
//            listener.onFragmentAttached(fm, fragment, context)
//        }
    }

    private fun registerFragmentViewCreatedListener(
        fm: FragmentManager,
        fragment: Fragment,
        view: View,
        savedInstanceState: Bundle?
    ){
//        for (listener in fragmentLifecycleListeners) {
//            listener.onFragmentViewCreated(fm, fragment, view, savedInstanceState)
//        }
    }

    private fun registerActivityListener(activity: Activity){
//        for (listener in activityLifecycleListeners) {
//            listener.onActivityResumed(activity)
//        }
//
//        if (activity is AppCompatActivity) {
//            activity.supportFragmentManager.registerFragmentLifecycleCallbacks(this, true)
//        }
    }

    private fun unregisterActivityListener(activity: Activity){
//        for (listener in activityLifecycleListeners) {
//            listener.onActivityPaused(activity)
//        }
//
//        if (activity is AppCompatActivity) {
//            activity.supportFragmentManager.unregisterFragmentLifecycleCallbacks(this)
//        }
    }
}