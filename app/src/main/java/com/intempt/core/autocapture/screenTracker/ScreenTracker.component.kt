package com.intempt.core.autocapture.screenTracker

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.intempt.core.autocapture.BaseAutoCaptureComponent
import com.intempt.core.types.Constants
import javax.inject.Inject

internal class ScreenTrackerComponent @Inject constructor(
    private val srv: ScreenTrackerService,
): BaseAutoCaptureComponent() {


    override fun onActivityResumed(activity: Activity) {
        srv.setPageId()

        srv.logAndDispatch(
            activity,
            Constants.SCREEN.ACTIVITY.VIEW_EVENT_NAME,
            Constants.SCREEN.ACTIVITY.VIEW_ENTITY_NAME,
            Constants.SCREEN.ACTIVITY.EVENT_TYPE,
            Constants.SCREEN.ACTIVITY.VIEW_TYPE,
        )
    }

    override fun onActivityPaused(activity: Activity) {
        srv.logAndDispatch(
            activity,
            Constants.SCREEN.ACTIVITY.LEAVE_EVENT_NAME,
            Constants.SCREEN.ACTIVITY.LEAVE_ENTITY_NAME,
            Constants.SCREEN.ACTIVITY.EVENT_TYPE,
            Constants.SCREEN.ACTIVITY.VIEW_TYPE,
        )
    }

    override fun onFragmentViewCreated(fm: FragmentManager, fragment: Fragment, view: View, savedInstanceState: Bundle?) {}

    override fun onFragmentAttached(fm: FragmentManager, fragment: Fragment, context: Context) {
        srv.handleFragmentCallbacks(
            "onFragmentAttached",
            "addedFragment",
            fragment
        )
    }

    override fun onFragmentDetached(fm: FragmentManager, fragment: Fragment) {
        srv.handleFragmentCallbacks(
            "onFragmentDetached",
            "removedFragment",
            fragment
        )

    }

    override fun onFragmentResumed(fm: FragmentManager, fragment: Fragment) {
        srv.handleFragmentCallbacks(
            "onFragmentResumed",
            "visibleFragment",
            fragment
        )

        srv.logAndDispatch(
            fragment.requireActivity(),
            Constants.SCREEN.FRAGMENT.EVENT_NAME,
            Constants.SCREEN.FRAGMENT.ENTITY_NAME,
            Constants.SCREEN.FRAGMENT.EVENT_TYPE,
            Constants.SCREEN.FRAGMENT.VIEW_TYPE,
        )
    }
}