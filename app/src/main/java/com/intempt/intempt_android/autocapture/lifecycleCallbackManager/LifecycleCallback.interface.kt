package com.intempt.intempt_android.autocapture.lifecycleCallbackManager

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager

// For activity lifecycle events
interface ActivityLifecycleListener {
    fun onActivityResumed(activity: Activity)
    fun onActivityPaused(activity: Activity)

}

// For fragment lifecycle events
interface FragmentLifecycleListener {
    fun onFragmentViewCreated(
        fm: FragmentManager,
        fragment: Fragment,
        view: View,
        savedInstanceState: Bundle?
    )

    fun onFragmentResumed(fm: FragmentManager, fragment: Fragment)
    fun onFragmentAttached(fm: FragmentManager, fragment: Fragment, context: Context)
    fun onFragmentDetached(fm: FragmentManager, fragment: Fragment)
}