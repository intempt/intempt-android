package com.intempt.core.autocapture.lifecycleCallbacksTracker

import android.app.Activity
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.intempt.core.types.Constants
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class LifecycleCallbackService @Inject constructor(
    private val screenTrackerSrv:ScreenTrackerService,
    private val changeTrackerSrv:ChangeTrackerService,
){

//screenTrackerSrv
    fun handleScreenView(activity: Activity){
//        screenTrackerSrv.storePageId()
//
//        screenTrackerSrv.logAndDispatch(
//            activity,
//            Constants.SCREEN.ACTIVITY.VIEW_EVENT_NAME,
//            Constants.SCREEN.ACTIVITY.VIEW_ENTITY_NAME,
//            Constants.SCREEN.ACTIVITY.EVENT_TYPE,
//            Constants.SCREEN.ACTIVITY.VIEW_TYPE,
//        )
    }

    fun handleScreenLeave(activity: Activity){
//        screenTrackerSrv.logAndDispatch(
//            activity,
//            Constants.SCREEN.ACTIVITY.LEAVE_EVENT_NAME,
//            Constants.SCREEN.ACTIVITY.LEAVE_ENTITY_NAME,
//            Constants.SCREEN.ACTIVITY.EVENT_TYPE,
//            Constants.SCREEN.ACTIVITY.VIEW_TYPE,
//        )
    }

    fun handleFragmentVisibility(fragment: Fragment){
//        screenTrackerSrv.handleFragmentCallbacks(
//            "onFragmentResumed",
//            "visibleFragment",
//            fragment
//        )
//
//        screenTrackerSrv.logAndDispatch(
//            fragment.requireActivity(),
//            Constants.SCREEN.FRAGMENT.EVENT_NAME,
//            Constants.SCREEN.FRAGMENT.ENTITY_NAME,
//            Constants.SCREEN.FRAGMENT.EVENT_TYPE,
//            Constants.SCREEN.FRAGMENT.VIEW_TYPE,
//        )
    }

    fun handleFragmentAdd(fragment: Fragment){
//        screenTrackerSrv.handleFragmentCallbacks(
//            "onFragmentAttached",
//            "addedFragment",
//            fragment
//        )
    }

    fun handleFragmentRemove(fragment: Fragment){
//        screenTrackerSrv.handleFragmentCallbacks(
//            "onFragmentDetached",
//            "removedFragment",
//            fragment
//        )
    }

//changeTrackerSrv
    fun handleChangeEventRegistrationInFragment(fragment: Fragment, lifecycleName:String){
//        (fragment.view as? ViewGroup)?.let { _ ->
//            registerChangeEventListener(fragment.requireActivity())
//        }
    }

    fun unregisterChangeEventListener(activity: Activity){
        //changeTrackerSrv.unregisterListener(activity)
    }

    fun registerChangeEventListener(activity: Activity){
       // changeTrackerSrv.registerListener(activity)
    }



}