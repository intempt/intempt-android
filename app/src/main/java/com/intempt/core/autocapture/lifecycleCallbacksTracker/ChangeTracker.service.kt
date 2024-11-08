package com.intempt.core.autocapture.lifecycleCallbacksTracker

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
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
internal open class ChangeTrackerService @Inject constructor(
    private val eventPool: EventPoolManagerService,
    val logger: LoggerManagerService,
    private val utils: UtilsService,
) {

    private val debounceDelay = Constants.DEBOUNCE_DELAY

    fun handleChangeListenerRegistration(view: View, activity:Activity) {
        logger.log("ChangeTrackerService | Start registration for ${view.javaClass.name}")
        val errorMessage = "ChangeTrackerService | ChangeTracker Error. Handling view: ${view::class.simpleName}"
        utils.withTryCatch(errorMessage){
            when (view) {
                is RadioButton -> handleRadioButton(view, activity)
                is CheckBox, is ToggleButton, is CompoundButton -> handleCompoundButton(view as CompoundButton, activity)
                is SeekBar -> handleSeekBar(view, activity)
                is Spinner -> handleSpinner(view, activity)
                is EditText -> handleEditText(view, activity)
                is DatePicker -> handleDatePicker(view, activity)
                is RatingBar -> handleRatingBar(view, activity)
                is TimePicker -> handleTimePicker(view, activity)
                is ListView -> handleListView(view, activity)
                is ComposeView  -> handleComposeView()
                else -> {
                    logger.log("ChangeTrackerService | Couldn't register change event for ${view.javaClass.name}")
                }
            }
        }

    }

    fun logAndDispatch(view: View, activity: Activity, viewType: String) {
        val errorMessage = "ChangeTrackerService | Error handling $viewType view: ${view::class.simpleName}";
        utils.withTryCatch(errorMessage) {
            logger.log("ChangeTrackerService | Change for $viewType")

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
    }

    private fun setupHandler(eventListener: (Handler, Array<Runnable?>) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        val runnableWrapper: Array<Runnable?> = arrayOfNulls(1)
        utils.withTryCatch("AutoCapture:ChangeTrackerService | ChangeTracker Error handling handler") {
            eventListener(handler, runnableWrapper)
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
        logger.log("AutoCapture:ChangeTrackerService | ChangeTracker Jetpack Compose View detected - not supported yet")
    }

    //SwitchCompat, MaterialSwitch, CheckBox, RadioButton, and ToggleButton
    private fun handleCompoundButton(view: CompoundButton, activity:Activity){
        logger.log("ChangeTrackerService | Perform CompoundButton registration for $${view.javaClass.name}")
        setupHandler { handler, runnableWrapper ->
            view.setOnCheckedChangeListener { _, isChecked ->
                logger.log("ChangeTrackerService | Listener triggered for ${view.javaClass.name} with state $isChecked")

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
                }
            }
        }
    }

    private fun handleEditText(view: EditText, activity:Activity){
        val elementName = "EditText"
        logger.log("AutoCapture:ChangeTrackerService | Invoke handleEditText")
        setupHandler { handler, runnableWrapper ->
            view.doAfterTextChanged  { _ ->
                runnableWrapper[0] = debounceAndLog(handler, runnableWrapper[0], view, activity, elementName)
            }
        }
    }

    private fun handleSeekBar(view: SeekBar, activity:Activity){
        logger.log("Register SeekBar")

        val elementName = "SeekBar"
        setupHandler { handler, runnableWrapper ->
            view.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    logger.log("invoke  onProgressChanged")
                        runnableWrapper[0] = debounceAndLog(handler, runnableWrapper[0], view, activity, elementName)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
    }

    private fun handleSpinner(view: Spinner, activity:Activity){
        val elementName = "Spinner"
        setupHandler { handler, runnableWrapper ->
            view.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, childView: View, position: Int, id: Long) {
                    runnableWrapper[0] = debounceAndLog(handler, runnableWrapper[0], view, activity, elementName)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
    }

    private fun handleDatePicker(view: DatePicker, activity: Activity) {
        val elementName = "DatePicker"
        setupHandler { handler, runnableWrapper ->
            view.setOnDateChangedListener { _, _, _, _ ->
                runnableWrapper[0] = debounceAndLog(handler, runnableWrapper[0], view, activity, elementName)
            }
        }
    }

    private fun handleRatingBar(view: RatingBar, activity: Activity) {
        val elementName = "RatingBar"
        setupHandler { handler, runnableWrapper ->
            view.setOnRatingBarChangeListener { _, _, _ ->
                runnableWrapper[0] = debounceAndLog(handler, runnableWrapper[0], view, activity, elementName)
            }
        }
    }

    private fun handleTimePicker(view: TimePicker, activity: Activity) {
        val elementName = "TimePicker"
        setupHandler { handler, runnableWrapper ->
            view.setOnTimeChangedListener { _, _, _ ->
                runnableWrapper[0] = debounceAndLog(handler, runnableWrapper[0], view, activity, elementName)
            }
        }
    }

    private fun handleListView(view: ListView, activity: Activity) {
        val elementName = "ListView Item"
        setupHandler { handler, runnableWrapper ->
            view.setOnItemClickListener { parent, childView, position, id ->
                if (childView != null) {
                    runnableWrapper[0] = debounceAndLog(handler, runnableWrapper[0], view, activity, elementName)
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

