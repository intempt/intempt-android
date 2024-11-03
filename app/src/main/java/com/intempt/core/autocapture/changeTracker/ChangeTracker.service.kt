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
import androidx.compose.ui.platform.ComposeView
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.materialswitch.MaterialSwitch
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.UtilsService
import com.intempt.core.services.eventPool.EventPoolManagerService
import com.intempt.core.types.Constants
import com.intempt.core.types.DispatchEventProps
import com.intempt.core.types.UiEventProps
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
internal class ChangeTrackerService @Inject constructor(
    private val eventPool: EventPoolManagerService,
    private val logger: LoggerManagerService,
    private val utils: UtilsService,
) {

    private val debounceDelay = Constants.DEBOUNCE_DELAY

    private fun logAndDispatch(view: View, activity: Activity, viewType: String) {
        val errorMessage = "AutoCapture | ChangeTracker Error handling $viewType view: ${view::class.simpleName}";
        utils.withTryCatch(errorMessage) {
            logger.log("AutoCapture | Change for $viewType")
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
        utils.withTryCatch("AutoCapture | ChangeTracker Error handling handler") {
            eventListener(handler, runnableWrapper)
        }

    }

    private fun dispatchEvent(props: UiEventProps) {
        val (activity, view) = props;
        eventPool.dispatchEvent(
            DispatchEventProps(
                eventName = Constants.CHANGE.EVENT_NAME,
                entityName = Constants.CHANGE.ENTITY_NAME,
                type = Constants.CHANGE.EVENT_TYPE,
                context = activity,
                view = view
            )
        )
    }

    fun handleChangeListenerRegistration(view: View, activity:Activity) {
        utils.withTryCatch("AutoCapture | ChangeTracker Error. Handling view: ${view::class.simpleName}" ){
            when (view) {
                is RadioButton -> handleRadioButton(view, activity)
                is CheckBox,
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
               // is ViewGroup -> handleChangeListenerRegistration(view, activity)
            }
        }

    }

    private fun debounceAndLog(
        handler: Handler,
        currentRunnable: Runnable?,
        view: View,
        activity: Activity,
        viewType: String
    ): Runnable {
        return utils.debounce(handler, debounceDelay, currentRunnable) {
            logAndDispatch(view, activity, viewType)
        }
    }

    private fun handleComposeView(){
        logger.log("AutoCapture | ChangeTracker Jetpack Compose View detected - not supported yet")
    }

    //SwitchCompat, MaterialSwitch, CheckBox, RadioButton, and ToggleButton
    private fun handleCompoundButton(view: CompoundButton, activity:Activity){
        setupHandler { handler, runnableWrapper ->
            view.setOnCheckedChangeListener { _, _ ->
                runnableWrapper[0] = utils.debounce(handler, debounceDelay, runnableWrapper[0]) {
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

    private fun handleRadioButton(view: CompoundButton, activity:Activity){
        val elementName = "RadioButton"
        setupHandler { handler, runnableWrapper ->
            view.setOnCheckedChangeListener { _, isChecked  ->
                if (isChecked) {
                    runnableWrapper[0] = debounceAndLog(handler, runnableWrapper[0], view, activity, elementName)
//                    runnableWrapper[0] = utils.debounce(handler, debounceDelay, runnableWrapper[0]) {
//                        logAndDispatch(view, activity, "RadioButton")
//                    }
                }
            }
        }
    }

    private fun handleEditText(view: EditText, activity:Activity){
        val elementName = "EditText"
        setupHandler { handler, runnableWrapper ->
            view.doAfterTextChanged  { text ->
                runnableWrapper[0] = debounceAndLog(handler, runnableWrapper[0], view, activity, elementName)
//                runnableWrapper[0] = utils.debounce(handler, debounceDelay, runnableWrapper[0]){
//                    logAndDispatch(view, activity, "EditText")
//                }
            }
        }
    }

    private fun handleSeekBar(view: SeekBar, activity:Activity){
        var isInitialized = false
        val elementName = "SeekBar"
        setupHandler { handler, runnableWrapper ->
            view.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (isInitialized) {
                        runnableWrapper[0] = debounceAndLog(handler, runnableWrapper[0], view, activity, elementName)
//                        runnableWrapper[0] = utils.debounce(handler, debounceDelay,  runnableWrapper[0]){
//                            logAndDispatch(view, activity, "SeekBar")
//                        }
                    }
                    isInitialized = true

                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
    }

    private fun handleSpinner(view: Spinner, activity:Activity){
        var isInitialized = false
        val elementName = "Spinner"
        setupHandler { handler, runnableWrapper ->
            view.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, childView: View, position: Int, id: Long) {
                    runnableWrapper[0] = debounceAndLog(handler, runnableWrapper[0], view, activity, elementName)
//                    if (isInitialized) {
//                        runnableWrapper[0] = utils.debounce(handler, debounceDelay, runnableWrapper[0]){
//                            logAndDispatch(childView, activity, "Spinner")
//                        }
//                    }

                    isInitialized = true
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
    }

    private fun handleDatePicker(view: DatePicker, activity: Activity) {
        val elementName = "DatePicker"
        setupHandler { handler, runnableWrapper ->
            view.setOnDateChangedListener { _, year, monthOfYear, dayOfMonth ->
                runnableWrapper[0] = debounceAndLog(handler, runnableWrapper[0], view, activity, elementName)
//                runnableWrapper[0] = utils.debounce(handler, debounceDelay, runnableWrapper[0]){
//                    logAndDispatch(view, activity, "DatePicker")
//                }
            }
        }
    }

    private fun handleRatingBar(view: RatingBar, activity: Activity) {
        var isInitialized = false
        val elementName = "RatingBar"
        setupHandler { handler, runnableWrapper ->
            view.setOnRatingBarChangeListener { _, _, _ ->
                runnableWrapper[0] = debounceAndLog(handler, runnableWrapper[0], view, activity, elementName)
//                if (isInitialized) {
//                    runnableWrapper[0] = utils.debounce(handler, debounceDelay, runnableWrapper[0]){
//                        logAndDispatch(view, activity, "RatingBar")
//                    }
//                }
                isInitialized = true
            }
        }
    }

    private fun handleTimePicker(view: TimePicker, activity: Activity) {
        val elementName = "TimePicker"
        setupHandler { handler, runnableWrapper ->
            view.setOnTimeChangedListener { _, hourOfDay, minute ->
                runnableWrapper[0] = debounceAndLog(handler, runnableWrapper[0], view, activity, elementName)
//                runnableWrapper[0] = utils.debounce(handler, debounceDelay, runnableWrapper[0]){
//                    logAndDispatch(view, activity, "TimePicker")
//                }
            }
        }
    }

    private fun handleListView(view: ListView, activity: Activity) {
        val elementName = "ListView Item"
        setupHandler { handler, runnableWrapper ->
            view.setOnItemClickListener { parent, childView, position, id ->
                if (childView != null) {
                    runnableWrapper[0] = debounceAndLog(handler, runnableWrapper[0], view, activity, elementName)

//                    runnableWrapper[0] = utils.debounce(handler, debounceDelay, runnableWrapper[0]) {
//                        logAndDispatch(childView, activity, "ListView Item Clicked at $position")
//                    }
                }
                else {
                    logger.error("setOnItemClickListener | Error: childView is null for ListView item at position $position")
                }
            }

            //TODO: if ListView supports focus-based selection
            view.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, childView: View?, position: Int, id: Long) {
                    if (childView != null) {
                        runnableWrapper[0] = debounceAndLog(handler, runnableWrapper[0], view, activity, elementName)
//                        runnableWrapper[0] = utils.debounce(handler, debounceDelay, runnableWrapper[0]) {
//                            logAndDispatch(childView, activity, "ListView Item Selected at $position")
//                        }
                    }
                    else {
                        logger.error("onItemSelected | Error: childView is null for ListView item selected at position $position")
                    }

                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
    }
}