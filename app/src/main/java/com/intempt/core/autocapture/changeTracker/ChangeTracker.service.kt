package com.intempt.core.autocapture.changeTracker

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
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
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.materialswitch.MaterialSwitch
import com.intempt.core.types.Constants
import com.intempt.core.services.Logger
import com.intempt.core.services.debounce
import com.intempt.core.services.withTryCatch
import com.intempt.core.types.UiEventProps
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
internal class ChangeTrackerService @Inject constructor(
  //  private val eventSrv: EventPool,
) {

    private val debounceDelay = Constants.DEBOUNCE_DELAY

    private fun logAndDispatch(view: View, activity: Activity, viewType: String) {
        val errorMessage = "AutoCapture | ChangeTracker Error handling $viewType view: ${view::class.simpleName}";
        withTryCatch(errorMessage) {
            Logger.log("AutoCapture | Change for $viewType")
            dispatchEvent(
                UiEventProps(
                    view = view,
                    activity = activity,
                    listenerType = "change"
                )
            )
        }
    }

    private fun setupHandler(
        eventListener: (Handler, Array<Runnable?>) -> Unit
    ) {
        val handler = Handler(Looper.getMainLooper())
        val runnableWrapper: Array<Runnable?> = arrayOfNulls(1)
        eventListener(handler, runnableWrapper)
    }

    private fun dispatchEvent(props: UiEventProps) {
        val (activity, view) = props;
//        eventSrv.dispatchEvent(
//            DispatchEventProps(
//                eventName = Constants.CHANGE.EVENT_NAME,
//                entityName = Constants.CHANGE.ENTITY_NAME,
//                type = Constants.CHANGE.EVENT_TYPE,
//                event = null,
//                context = activity,
//                view = view
//            )
//        )
    }


    fun handleComposeView(){
        Logger.log("AutoCapture | ChangeTracker Jetpack Compose View detected - not supported yet")
    }

    //SwitchCompat, MaterialSwitch, CheckBox, RadioButton, and ToggleButton
    fun handleCompoundButton(view: CompoundButton, activity:Activity){
        setupHandler { handler, runnableWrapper ->
            view.setOnCheckedChangeListener { _, _ ->
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
                    logAndDispatch(view, activity, buttonType)
                }
            }
        }
    }

    fun handleRadioButton(view: CompoundButton, activity:Activity){
        setupHandler { handler, runnableWrapper ->
            view.setOnCheckedChangeListener { _, isChecked  ->
                if (isChecked) {
                    runnableWrapper[0] = debounce(handler, debounceDelay, runnableWrapper[0]) {

                        logAndDispatch(view, activity, "RadioButton")
                    }
                }
            }
        }
    }

    fun handleEditText(view: EditText, activity:Activity){
        setupHandler { handler, runnableWrapper ->
            view.doAfterTextChanged  { text ->
                runnableWrapper[0] = debounce(handler, debounceDelay, runnableWrapper[0]){
                    logAndDispatch(view, activity, "EditText")
                }
            }
        }
    }

    fun handleSeekBar(view: SeekBar, activity:Activity){
        var isInitialized = false
        setupHandler { handler, runnableWrapper ->
            view.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (isInitialized) {
                        runnableWrapper[0] = debounce(handler, debounceDelay,  runnableWrapper[0]){
                            logAndDispatch(view, activity, "SeekBar")
                        }
                    }
                    isInitialized = true

                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
    }

    fun handleSpinner(view: Spinner, activity:Activity){
        var isInitialized = false
        setupHandler { handler, runnableWrapper ->
            view.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, childView: View, position: Int, id: Long) {
//                    if (isInitialized) {
                        runnableWrapper[0] = debounce(handler, debounceDelay, runnableWrapper[0]){
                            logAndDispatch(childView, activity, "Spinner")
                        }
//                    }

                    isInitialized = true
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
    }

    fun handleDatePicker(view: DatePicker, activity: Activity) {
        setupHandler { handler, runnableWrapper ->
            view.setOnDateChangedListener { _, year, monthOfYear, dayOfMonth ->
                runnableWrapper[0] = debounce(handler, debounceDelay, runnableWrapper[0]){
                    logAndDispatch(view, activity, "DatePicker")
                }
            }
        }
    }

    fun handleRatingBar(view: RatingBar, activity: Activity) {
        var isInitialized = false
        setupHandler { handler, runnableWrapper ->
            view.setOnRatingBarChangeListener { _, _, _ ->
//                if (isInitialized) {
                    runnableWrapper[0] = debounce(handler, debounceDelay, runnableWrapper[0]){
                        logAndDispatch(view, activity, "RatingBar")
                    }
//                }
                isInitialized = true
            }
        }
    }

    fun handleTimePicker(view: TimePicker, activity: Activity) {
        setupHandler { handler, runnableWrapper ->
            view.setOnTimeChangedListener { _, hourOfDay, minute ->
                runnableWrapper[0] = debounce(handler, debounceDelay, runnableWrapper[0]){
                    logAndDispatch(view, activity, "TimePicker")
                }
            }
        }
    }

    fun handleListView(view: ListView, activity: Activity) {
        setupHandler { handler, runnableWrapper ->
            view.setOnItemClickListener { parent, childView, position, id ->
                if (childView != null) {
                    runnableWrapper[0] = debounce(handler, debounceDelay, runnableWrapper[0]) {
                        logAndDispatch(childView, activity, "ListView Item Clicked at $position")
                    }
                }
                else {
                    Logger.error("setOnItemClickListener | Error: childView is null for ListView item at position $position")
                }
            }

            //TODO: if ListView supports focus-based selection
            view.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, childView: View?, position: Int, id: Long) {
                    if (childView != null) {
                        runnableWrapper[0] = debounce(handler, debounceDelay, runnableWrapper[0]) {
                            logAndDispatch(childView, activity, "ListView Item Selected at $position")
                        }
                    }
                    else {
                        Logger.error("onItemSelected | Error: childView is null for ListView item selected at position $position")
                    }

                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
    }
}