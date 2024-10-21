package com.intempt.intempt_android.autocapture.changeTracker

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.ToggleButton
import androidx.appcompat.widget.SwitchCompat
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import com.google.android.material.materialswitch.MaterialSwitch
import com.intempt.intempt_android.Logger
import com.intempt.intempt_android.eventPool.EventPool
import com.intempt.intempt_android.types.DispatchEventProps

import javax.inject.Inject

class ChangeTracker @Inject constructor(
    private val eventSrv: EventPool
) {

    fun registerForActivity(activity: Activity) {
        // Get the root view of the activity
        (activity.findViewById(android.R.id.content) as? ViewGroup)?.let { rootView ->
            registerChangeListenersRecursively(rootView, activity)
        }
    }

    fun registerForFragment(fragment: Fragment) {
        // Get the root view of the fragment
        (fragment.view as? ViewGroup)?.let { rootView ->
            registerChangeListenersRecursively(rootView, fragment.requireActivity())
        }
    }

    private fun registerChangeListenersRecursively(viewGroup: ViewGroup, activity:Activity) {
        for (i in 0 until viewGroup.childCount) {
            when (val view = viewGroup.getChildAt(i)) {
                is CheckBox -> handleCompoundButton(view, activity)
                is RadioButton  -> handleCompoundButton(view, activity)
                is ToggleButton -> handleCompoundButton(view, activity)
                is CompoundButton -> handleCompoundButton(view, activity)
                is SeekBar -> handleSeekBar(view, activity)
                is Spinner -> handleSpinner(view, activity)
                is EditText -> handleEditText(view, activity)


                // Recursively check child views if it's a ViewGroup
                is ViewGroup -> registerChangeListenersRecursively(view, activity)
            }
        }
    }

    //SwitchCompat, MaterialSwitch, CheckBox, RadioButton, and ToggleButton
    private fun handleCompoundButton(view: CompoundButton, activity:Activity){
        view.setOnCheckedChangeListener { _, isChecked ->
            val buttonType = when (view) {
                is Switch -> "Switch"
                is MaterialSwitch -> "MaterialSwitch"
                is SwitchCompat -> "SwitchCompat"
                is CheckBox -> "CheckBox"
                is RadioButton -> "RadioButton"
                is ToggleButton -> "ToggleButton"
                else -> "CompoundButton"
            }

            Logger.log("AutoCapture | Change for $buttonType")
            eventSrv.dispatchEvent(
                DispatchEventProps(
                    eventName = "Change Event",
                    entityName="changeEvent",
                    type = "change",
                    event = null,
                    context = activity,
                    view = view
                )
            )
        }

    }

    private fun handleToggleButton(view: ToggleButton, activity:Activity){
        view.setOnCheckedChangeListener { _, isChecked ->
            Logger.log("AutoCapture | Change for ToggleButton")
            eventSrv.dispatchEvent(
                DispatchEventProps(
                    eventName = "Change Event",
                    entityName="changeEvent",
                    type = "change",
                    event = null,
                    context = activity,
                    view = view
                )
            )
        }

    }

    private fun handleCheckBox(view: CheckBox, activity:Activity){
        view.setOnCheckedChangeListener { _, isChecked ->
            Logger.log("AutoCapture | Change for CheckBox")
            eventSrv.dispatchEvent(
                DispatchEventProps(
                    eventName = "Change Event",
                    entityName="changeEvent",
                    type = "change",
                    event = null,
                    context = activity,
                    view = view
                )
            )
        }
    }

    private fun handleRadioButton(view: RadioButton, activity:Activity){
        view.setOnCheckedChangeListener { _, isChecked ->
            Logger.log("AutoCapture | Change for RadioButton")
            eventSrv.dispatchEvent(
                DispatchEventProps(
                    eventName = "Change Event",
                    entityName="changeEvent",
                    type = "change",
                    event = null,
                    context = activity,
                    view = view
                )
            )
        }

    }

    private fun handleSwitch(view: CompoundButton, activity:Activity){
        view.setOnCheckedChangeListener { _, isChecked ->
            Logger.log("AutoCapture | Change for Switch")
            eventSrv.dispatchEvent(
                DispatchEventProps(
                    eventName = "Change Event",
                    entityName="changeEvent",
                    type = "change",
                    event = null,
                    context = activity,
                    view = view
                )
            )
        }

    }


    private fun handleEditText(view: EditText, activity:Activity){
        view.doOnTextChanged { text, _, _, _ ->
            Logger.log("AutoCapture | Change for EditText")

            eventSrv.dispatchEvent(
                DispatchEventProps(
                    eventName = "Change Event",
                    entityName="changeEvent",
                    type = "change",
                    event = null,
                    context = activity,
                    view = view
                )
            )
        }
    }

    private fun handleSeekBar(view: SeekBar, activity:Activity){
        view.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                Logger.log("AutoCapture | Change for SeekBar")
                eventSrv.dispatchEvent(
                    DispatchEventProps(
                        eventName = "Change Event",
                        entityName="changeEvent",
                        type = "change",
                        event = null,
                        context = activity,
                        view = view
                    )
                )
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

    }

    private fun handleSpinner(view: Spinner, activity:Activity){
        view.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                Logger.log("AutoCapture | Change for Spinner")
                eventSrv.dispatchEvent(
                    DispatchEventProps(
                        eventName = "Change Event",
                        entityName="changeEvent",
                        type = "change",
                        event = null,
                        context = activity,
                        view = view
                    )
                )
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

    }



}