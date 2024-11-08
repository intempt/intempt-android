package com.intempt.core.autocapture.lifecycleCallbacksTracker

import android.R
import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.DatePicker
import android.widget.EditText
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RatingBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TimePicker
import android.widget.ToggleButton
import androidx.fragment.app.Fragment
import com.intempt.core.types.Constants
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class LifecycleCallbackService @Inject constructor(
    private val screenTrackerSrv:ScreenTrackerService,
    private val changeTrackerSrv:ChangeTrackerService
){

    private lateinit var changeObserver: ViewTreeObserver.OnGlobalLayoutListener
    private val registeredViews = mutableSetOf<View>()

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
        screenTrackerSrv.handleFragmentCallbacks(
            "onFragmentDetached",
            "removedFragment",
            fragment
        )
    }


    fun handleChangeEventRegistrationInFragment(fragment: Fragment){
        (fragment.view as? ViewGroup)?.let { rootView ->
            registerChangeListenersRecursively(rootView, fragment.requireActivity())
        }
    }

    fun unregisterChangeEventListener(activity: Activity){
        activity.window.decorView.viewTreeObserver.removeOnGlobalLayoutListener(changeObserver)
    }

    fun handleChangeEventRegistrationInActivity(activity: Activity){
        changeObserver = ViewTreeObserver.OnGlobalLayoutListener {
            val rootView = activity.window.decorView.findViewById<ViewGroup>(R.id.content)
            rootView?.let { view ->
               when(view){
                   is RadioButton -> changeTrackerSrv.logAndDispatch(view, activity, "RadioButton")
                   is CheckBox, is ToggleButton, is CompoundButton -> changeTrackerSrv.logAndDispatch(view, activity, "CompoundButton")
                   is SeekBar -> changeTrackerSrv.logAndDispatch(view, activity, "SeekBar")
                   is Spinner -> changeTrackerSrv.logAndDispatch(view, activity, "Spinner")
                   is EditText -> changeTrackerSrv.logAndDispatch(view, activity, "EditText")
                   is DatePicker -> changeTrackerSrv.logAndDispatch(view, activity, "DatePicker")
                   is RatingBar -> changeTrackerSrv.logAndDispatch(view, activity, "RatingBar")
                   is TimePicker -> changeTrackerSrv.logAndDispatch(view, activity, "TimePicker")
                   is ListView -> changeTrackerSrv.logAndDispatch(view, activity, "ListView")
                   else -> registerChangeListenersRecursively(view, activity)
               }
            }
            unregisterChangeEventListener(activity)
        }
        activity.window.decorView.viewTreeObserver.addOnGlobalLayoutListener(changeObserver)
    }

    private fun registerChangeListenersRecursively(viewGroup: ViewGroup, activity:Activity){
        changeTrackerSrv.logger.log("LifecycleCallbackService | Invoke registerChangeListenersRecursively")
        for (i in 0 until viewGroup.childCount) {
            val view = viewGroup.getChildAt(i);
            changeTrackerSrv.logger.log("LifecycleCallbackService javaClass | ${view.javaClass.name}")
            changeTrackerSrv.logger.log("LifecycleCallbackService simpleName | ${view::class.simpleName}")
            if (view is ViewGroup) {
                registerChangeListenersRecursively(view, activity)
            }
            else{
                if (!registeredViews.contains(view)) {
                    registeredViews.add(view)
                    changeTrackerSrv.handleChangeListenerRegistration(view, activity)
                }
            }

        }
    }


}