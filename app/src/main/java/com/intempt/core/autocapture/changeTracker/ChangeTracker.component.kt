package com.intempt.core.autocapture.changeTracker

import android.R
import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
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
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.intempt.core.autocapture.BaseAutoCaptureComponent
import com.intempt.core.services.withTryCatch

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class ChangeTrackerComponent @Inject constructor(
    private val srv: ChangeTrackerService
): BaseAutoCaptureComponent() {



    override fun onActivityResumed(activity: Activity) {
        registerForActivity(activity)
    }

    override fun onFragmentViewCreated(
        fm: FragmentManager,
        fragment: Fragment,
        view: View,
        savedInstanceState: Bundle?
    ) {
        registerForFragment(fragment)
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

    private fun registerForActivity(activity: Activity) {
        withTryCatch("AutoCapture | ChangeTracker Error handling activity"){
            (activity.findViewById(R.id.content) as? ViewGroup)?.let { rootView ->
                registerChangeListenersRecursively(rootView, activity)
            }
        }
    }

    private fun registerForFragment(fragment: Fragment) {
        withTryCatch("AutoCapture | ChangeTracker Error handling fragment"){
            (fragment.view as? ViewGroup)?.let { rootView ->
                registerChangeListenersRecursively(rootView, fragment.requireActivity())
            }
        }
    }

    private fun registerChangeListenersRecursively(viewGroup: ViewGroup, activity:Activity) {
        for (i in 0 until viewGroup.childCount) {
            val view = viewGroup.getChildAt(i);
            withTryCatch("AutoCapture | ChangeTracker Error handling view: ${view::class.simpleName}"){
                when (view) {
                    is RadioButton -> srv.handleRadioButton(view, activity)
                    is CheckBox,
                    is ToggleButton,
                    is CompoundButton -> srv.handleCompoundButton(view as CompoundButton, activity)
                    is SeekBar -> srv.handleSeekBar(view, activity)
                    is Spinner -> srv.handleSpinner(view, activity)
                    is EditText -> srv.handleEditText(view, activity)
                    is DatePicker -> srv.handleDatePicker(view, activity)
                    is RatingBar -> srv.handleRatingBar(view, activity)
                    is TimePicker -> srv.handleTimePicker(view, activity)
                    is ListView -> srv.handleListView(view, activity)
                    is ComposeView  -> srv.handleComposeView()

                    // Recursively check child views if it's a ViewGroup
                    is ViewGroup -> registerChangeListenersRecursively(view, activity)
                }
            }
        }
    }

}