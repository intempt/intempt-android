package com.intempt.intempt_android.autocapture.changeTracker

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
import com.intempt.intempt_android.types.Constants
import com.intempt.intempt_android.Logger
import com.intempt.intempt_android.configManager.ConfigManagerService
import com.intempt.intempt_android.debounce
import com.intempt.intempt_android.eventPool.EventPool
import com.intempt.intempt_android.types.DispatchEventProps
import com.intempt.intempt_android.withTryCatch
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
internal class ChangeTrackerService @Inject constructor(
    private val eventSrv: EventPool,
    private val config: ConfigManagerService
) {

    private val debounceDelay = Constants.DEBOUNCE_DELAY

    private fun logAndDispatch(view: View, activity: Activity, viewType: String) {
        val errorMessage = "AutoCapture | ChangeTracker Error handling $viewType view: ${view::class.simpleName}";
        withTryCatch(errorMessage) {
            Logger.log("AutoCapture | Change for $viewType")
            dispatchEvent(view, activity)
        }
    }

    private fun setupHandler(
        eventListener: (Handler, Array<Runnable?>) -> Unit
    ) {
        val handler = Handler(Looper.getMainLooper())
        val runnableWrapper: Array<Runnable?> = arrayOfNulls(1)
        eventListener(handler, runnableWrapper)
    }

    private fun dispatchEvent(view: View, activity: Activity) {
        eventSrv.dispatchEvent(
            DispatchEventProps(
                eventName = Constants.CHANGE.EVENT_NAME,
                entityName = Constants.CHANGE.ENTITY_NAME,
                type = Constants.CHANGE.EVENT_TYPE,
                event = null,
                context = activity,
                view = view
            )
        )
    }


    fun handleComposeView(){
        Logger.log("AutoCapture | ChangeTracker Jetpack Compose View detected - not supported yet")
    }

    //SwitchCompat, MaterialSwitch, CheckBox, RadioButton, and ToggleButton
    fun handleCompoundButton(view: CompoundButton, activity:Activity){
        setupHandler { handler, runnableWrapper ->
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
                    logAndDispatch(view, activity, buttonType)
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
        setupHandler { handler, runnableWrapper ->
            view.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    runnableWrapper[0] = debounce(handler, debounceDelay,  runnableWrapper[0]){
                        logAndDispatch(view, activity, "SeekBar")
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
    }

    fun handleSpinner(view: Spinner, activity:Activity){
        setupHandler { handler, runnableWrapper ->
            view.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, childView: View, position: Int, id: Long) {
                    runnableWrapper[0] = debounce(handler, debounceDelay, runnableWrapper[0]){
                        logAndDispatch(childView, activity, "Spinner")
                    }
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
        setupHandler { handler, runnableWrapper ->
            view.setOnRatingBarChangeListener { _, rating, _ ->
                runnableWrapper[0] = debounce(handler, debounceDelay, runnableWrapper[0]){
                    logAndDispatch(view, activity, "RatingBar")
                }
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
                runnableWrapper[0] = debounce(handler, debounceDelay, runnableWrapper[0]) {
                    logAndDispatch(childView, activity, "ListView Item Clicked at $position")
                }
            }

            //TODO: if ListView supports focus-based selection
            view.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, childView: View, position: Int, id: Long) {
                    runnableWrapper[0] = debounce(handler, debounceDelay, runnableWrapper[0]) {
                        logAndDispatch(childView, activity, "ListView Item Selected at $position")
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
    }
}