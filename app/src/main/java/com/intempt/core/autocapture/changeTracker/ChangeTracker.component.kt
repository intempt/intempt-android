package com.intempt.core.autocapture.changeTracker

import android.R
import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.intempt.core.autocapture.BaseAutoCaptureComponent
import com.intempt.core.services.LoggerManagerService


import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class ChangeTrackerComponent @Inject constructor(
    private val srv: ChangeTrackerService,
     logger: LoggerManagerService,

): BaseAutoCaptureComponent(logger) {

    override fun onActivityResumed(activity: Activity) {
        (activity.findViewById(R.id.content) as? ViewGroup)?.let { rootView ->
            registerChangeListenersRecursively(rootView, activity)
        }
    }

    override fun onFragmentViewCreated(
        fm: FragmentManager,
        fragment: Fragment,
        view: View,
        savedInstanceState: Bundle?
    ) {
        (fragment.view as? ViewGroup)?.let { rootView ->
            registerChangeListenersRecursively(rootView, fragment.requireActivity())
        }
    }

    override fun onActivityPaused(activity: Activity) {}

    override fun onFragmentResumed(fm: FragmentManager, fragment: Fragment) {
        TODO("Not yet implemented")
    }

    override fun onFragmentAttached(fm: FragmentManager, fragment: Fragment, context: Context) {
        TODO("Not yet implemented")
    }

    override fun onFragmentDetached(fm: FragmentManager, fragment: Fragment) {
        TODO("Not yet implemented")
    }

    private fun registerChangeListenersRecursively(viewGroup: ViewGroup, activity:Activity) {
        for (i in 0 until viewGroup.childCount) {
            val view = viewGroup.getChildAt(i);
            srv.handleChangeListenerRegistration(view, activity)
        }
    }

}