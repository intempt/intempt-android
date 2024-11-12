package com.intempt.core.autocapture.lifecycleCallbacksTracker

import android.app.Activity
import androidx.fragment.app.Fragment
import com.intempt.core.types.Constants
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class LifecycleCallbackService @Inject constructor(
    private val screenTrackerSrv:ScreenTrackerService,
    private val touchTrackerSrv:TouchTrackerService,
    private val changeTrackerSrv:ChangeTrackerService,
){

    private val visibleFragmentHashes = mutableSetOf<Int>()

//screenTrackerSrv
    fun handleScreenView(activity: Activity){
        screenTrackerSrv.storePageId()
        screenTrackerSrv.logAndDispatch(
            activity,
            Constants.SCREEN.ACTIVITY.VIEW_EVENT_NAME,
            Constants.SCREEN.ACTIVITY.VIEW_ENTITY_NAME,
            Constants.SCREEN.ACTIVITY.EVENT_TYPE,
            Constants.SCREEN.ACTIVITY.VIEW_TYPE,
        )
    }

    fun handleScreenLeave(activity: Activity){
        screenTrackerSrv.logAndDispatch(
            activity,
            Constants.SCREEN.ACTIVITY.LEAVE_EVENT_NAME,
            Constants.SCREEN.ACTIVITY.LEAVE_ENTITY_NAME,
            Constants.SCREEN.ACTIVITY.EVENT_TYPE,
            Constants.SCREEN.ACTIVITY.VIEW_TYPE,
        )
    }

    fun handleFragmentVisibility(fragment: Fragment){
        val fragmentHashCode = fragment.hashCode()
        if (visibleFragmentHashes.contains(fragmentHashCode)) {
            return
        }
        visibleFragmentHashes.add(fragmentHashCode)
//        println("ScreenTracker | handleFragmentVisibility ${fragment.javaClass.simpleName}:$fragmentHashCode")

        screenTrackerSrv.handleFragmentCallbacks(
            "onFragmentResumed",
            "visibleFragment",
            fragment
        )

        screenTrackerSrv.logAndDispatch(
            fragment.requireActivity(),
            Constants.SCREEN.FRAGMENT.EVENT_NAME,
            Constants.SCREEN.FRAGMENT.ENTITY_NAME,
            Constants.SCREEN.FRAGMENT.EVENT_TYPE,
            Constants.SCREEN.FRAGMENT.VIEW_TYPE,
        )
    }

    fun handleFragmentAdd(fragment: Fragment){
        screenTrackerSrv.handleFragmentCallbacks(
            "onFragmentAttached",
            "addedFragment",
            fragment
        )
    }

    fun handleFragmentRemove(fragment: Fragment){
        val fragmentHashCode = fragment.hashCode()
        screenTrackerSrv.handleFragmentCallbacks(
            "onFragmentDetached",
            "removedFragment",
            fragment
        )
        visibleFragmentHashes.remove(fragmentHashCode)
    }

//changeTrackerSrv
    fun registerChangeEventListener(activity: Activity){
        changeTrackerSrv.registerListener(activity)
    }

    fun registerTouchEventListener(activity: Activity){
        touchTrackerSrv.register(activity)
    }



}