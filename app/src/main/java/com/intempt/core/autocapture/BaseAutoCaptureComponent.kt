package com.intempt.core.autocapture

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager

import com.intempt.core.autocapture.lifecycleCallbackManager.ActivityLifecycleListener
import com.intempt.core.autocapture.lifecycleCallbackManager.FragmentLifecycleListener


open class BaseAutoCaptureComponent:BaseComponent(), ActivityLifecycleListener, FragmentLifecycleListener {
    override fun onActivityResumed(activity: Activity) {}

    override fun onActivityPaused(activity: Activity) {}

    override fun onFragmentViewCreated(
        fm: FragmentManager,
        fragment: Fragment,
        view: View,
        savedInstanceState: Bundle?
    ) {}

    override fun onFragmentAttached(fm: FragmentManager, fragment: Fragment, context: Context) {}

    override fun onFragmentResumed(fm: FragmentManager, fragment: Fragment) {}

    override fun onFragmentDetached(fm: FragmentManager, fragment: Fragment) {}
}