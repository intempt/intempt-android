package com.intempt.intempt_android.autocapture.changeTracker

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.DatePicker
import android.widget.EditText
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RatingBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TimePicker
import android.widget.ToggleButton
import androidx.appcompat.widget.SwitchCompat
import androidx.compose.ui.platform.ComposeView
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import com.google.android.material.materialswitch.MaterialSwitch
import com.intempt.intempt_android.Logger
import com.intempt.intempt_android.debounce
import com.intempt.intempt_android.withTryCatch


import javax.inject.Inject

internal class ChangeTrackerComponent @Inject constructor(
    private val srv: ChangeTrackerService
) {

    private val debounceDelay = 320L

    fun registerForActivity(activity: Activity) {
        withTryCatch("AutoCapture | ChangeTracker Error handling activity"){
            (activity.findViewById(android.R.id.content) as? ViewGroup)?.let { rootView ->
                registerChangeListenersRecursively(rootView, activity)
            }
        }
    }

    fun registerForFragment(fragment: Fragment) {
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
                    is CheckBox,
                    is RadioButton,
                    is ToggleButton,
                    is CompoundButton -> handleCompoundButton(view as CompoundButton, activity)
                    is SeekBar -> handleSeekBar(view, activity)
                    is Spinner -> handleSpinner(view, activity)
                    is EditText -> handleEditText(view, activity)
                    is DatePicker -> handleDatePicker(view, activity)
                    is RatingBar -> handleRatingBar(view, activity)
                    is TimePicker -> handleTimePicker(view, activity)
                    is ListView -> handleListView(view, activity)
                    is ComposeView  -> handleComposeView()

                    // Recursively check child views if it's a ViewGroup
                    is ViewGroup -> registerChangeListenersRecursively(view, activity)
                }
            }
        }
    }

    private fun handleComposeView(){
        Logger.log("AutoCapture | ChangeTracker Jetpack Compose View detected - not supported yet")
    }

    //SwitchCompat, MaterialSwitch, CheckBox, RadioButton, and ToggleButton
    private fun handleCompoundButton(view: CompoundButton, activity:Activity){
        srv.setupHandler { handler, runnableWrapper ->
            view.setOnCheckedChangeListener { _, isChecked ->
                runnableWrapper[0] = debounce(handler, debounceDelay, runnableWrapper[0]) {
                    val buttonType = when (view) {
                        is Switch -> "Switch"
                        is MaterialSwitch -> "MaterialSwitch"
                        is SwitchCompat -> "SwitchCompat"
                        is CheckBox -> "CheckBox"
                        is RadioButton -> "RadioButton"
                        is ToggleButton -> "ToggleButton"
                        else -> "CompoundButton"
                    }
                    srv.logAndDispatchChange(view, activity, buttonType)
                }
            }
        }
    }

    private fun handleEditText(view: EditText, activity:Activity){
        srv.setupHandler { handler, runnableWrapper ->
            view.doAfterTextChanged  { text ->
                runnableWrapper[0] = debounce(handler, debounceDelay, runnableWrapper[0]){
                    srv.logAndDispatchChange(view, activity, "EditText")
                }
            }
        }
    }

    private fun handleSeekBar(view: SeekBar, activity:Activity){
        srv.setupHandler { handler, runnableWrapper ->
            view.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    runnableWrapper[0] = debounce(handler, debounceDelay,  runnableWrapper[0]){
                        srv.logAndDispatchChange(view, activity, "SeekBar")
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
    }

    private fun handleSpinner(view: Spinner, activity:Activity){
        srv.setupHandler { handler, runnableWrapper ->
            view.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, childView: View, position: Int, id: Long) {
                    runnableWrapper[0] = debounce(handler, debounceDelay, runnableWrapper[0]){
                        srv.logAndDispatchChange(childView, activity, "Spinner")
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
    }

    private fun handleDatePicker(view: DatePicker, activity: Activity) {
        srv.setupHandler { handler, runnableWrapper ->
            view.setOnDateChangedListener { _, year, monthOfYear, dayOfMonth ->
                runnableWrapper[0] = debounce(handler, debounceDelay, runnableWrapper[0]){
                    srv.logAndDispatchChange(view, activity, "DatePicker")
                }
            }
        }
    }

    private fun handleRatingBar(view: RatingBar, activity: Activity) {
        srv.setupHandler { handler, runnableWrapper ->
            view.setOnRatingBarChangeListener { _, rating, _ ->
                runnableWrapper[0] = debounce(handler, debounceDelay, runnableWrapper[0]){
                    srv.logAndDispatchChange(view, activity, "RatingBar")
                }

            }
        }
    }

    private fun handleTimePicker(view: TimePicker, activity: Activity) {
        srv.setupHandler { handler, runnableWrapper ->
            view.setOnTimeChangedListener { _, hourOfDay, minute ->
                runnableWrapper[0] = debounce(handler, debounceDelay, runnableWrapper[0]){
                    srv.logAndDispatchChange(view, activity, "TimePicker")
                }
            }
        }
    }

    private fun handleListView(view: ListView, activity: Activity) {
        srv.setupHandler { handler, runnableWrapper ->
            view.setOnItemClickListener { parent, childView, position, id ->
                runnableWrapper[0] = debounce(handler, debounceDelay, runnableWrapper[0]) {
                    srv.logAndDispatchChange(childView, activity, "ListView Item Clicked at $position")
                }
            }

            //TODO: if ListView supports focus-based selection
            view.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, childView: View, position: Int, id: Long) {
                    runnableWrapper[0] = debounce(handler, debounceDelay, runnableWrapper[0]) {
                        srv.logAndDispatchChange(childView, activity, "ListView Item Selected at $position")
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
    }

}