package com.intempt.core.autocapture.lifecycleCallbacksTracker
import android.R
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
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
import android.widget.TextView
import android.widget.TimePicker
import android.widget.ToggleButton
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.RecyclerView
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

    private val previousStateMap = mutableMapOf<Int, Any?>()
    private val debounceDelay = Constants.DEBOUNCE_DELAY
    private val handler = Handler(Looper.getMainLooper())
    private val runnableWrapper: Array<Runnable?> = arrayOfNulls(1)


    fun registerListener(activity: Activity){
        (activity.findViewById(R.id.content) as? ViewGroup)?.let { rootView ->
            findAndRegisterChangeEvent(rootView, activity)
        }
    }

    private fun findAndRegisterChangeEvent(viewGroup: ViewGroup, activity: Activity){
        logger.log("Invoke findAndInvokeChangeEvent")
        for (i in 0 until viewGroup.childCount) {
            when( val view = viewGroup.getChildAt(i)){
                is RecyclerView -> {
                    val layoutManager = view.layoutManager
                    for (j in 0 until view.childCount) {
                        val childView = layoutManager?.getChildAt(i)
                        if (childView != null) {
                            findAndRegisterChangeEvent(view, activity)
                        }
                    }
                }
                is ViewGroup -> findAndRegisterChangeEvent(view, activity)
                else -> handleChangeListenerRegistration(view, activity)
            }
        }
    }

    private fun checkViews(view:View): Boolean{
        return view is EditText
                || view is Spinner
                || view is ToggleButton
                || view is CheckBox
                || view is RadioButton
                || view is CompoundButton
                || view is TextView
                || view is SeekBar
                || view is RatingBar
                || view is TimePicker
                || view is DatePicker
                || view is ListView
    }

    private fun getViewValue(view: View): Any? {
        return when (view) {
            is CompoundButton -> view.isChecked
            is EditText -> view.text.toString()
            is Spinner -> view.selectedItem
            is SeekBar -> view.progress
            is RatingBar -> view.rating
            is DatePicker -> "${view.year}-${view.month}-${view.dayOfMonth}"
            is TimePicker -> "${view.hour}:${view.minute}"
            is ListView -> view.selectedItemId
            else -> null
        }
    }

    private fun debounceAndLog(
        handler: Handler,
        currentRunnable: Runnable?,
        view: View,
        activity: Activity,
    ): Runnable {
        return utils.debounce(handler, debounceDelay, currentRunnable) {
            eventPool.dispatchEvent(
                DispatchEventProps(
                    eventName = Constants.CHANGE.EVENT_NAME,
                    entityName = Constants.CHANGE.ENTITY_NAME,
                    type = Constants.CHANGE.EVENT_TYPE,
                    context = activity,
                    view = view
                ),
                "ChangeTrackerService"
            )
        }
    }



    private fun handleChangeListenerRegistration(view: View, activity:Activity) {
        logger.log("ChangeTrackerService | Start registration for ${view.javaClass.name}")
        val errorMessage = "ChangeTrackerService | ChangeTracker Error. Handling view: ${view::class.simpleName}"
        utils.withTryCatch(errorMessage){
            when (view) {
                is RadioButton -> handleRadioButton(view, activity)
                is CompoundButton -> handleCompoundButton(view, activity)
                is SeekBar -> handleSeekBar(view, activity)
                is Spinner -> handleSpinner(view, activity)
                is EditText -> handleEditText(view, activity)
                is DatePicker -> handleDatePicker(view, activity)
                is RatingBar -> handleRatingBar(view, activity)
                is TimePicker -> handleTimePicker(view, activity)
                is ListView -> handleListView(view, activity)
                else -> {
                    logger.log("ChangeTrackerService | Couldn't register change event for ${view.javaClass.name}")
                }
            }
        }

    }

    private fun handleCompoundButton(view: CompoundButton, activity:Activity){
        logger.log("ChangeTrackerService | Perform CompoundButton registration for $${view.javaClass.name}")

            view.setOnCheckedChangeListener { _, isChecked ->
                logger.log("ChangeTrackerService | Listener triggered for ${view.javaClass.name} with state $isChecked")

                runnableWrapper[0] = debounceAndLog(handler, runnableWrapper[0], view, activity)
            }

    }

    private fun handleRadioButton(view: CompoundButton, activity:Activity){
        view.setOnCheckedChangeListener { _, isChecked  ->
            if (isChecked) {
                runnableWrapper[0] = debounceAndLog(handler, runnableWrapper[0], view, activity)
            }
        }
    }

    private fun handleEditText(view: EditText, activity:Activity){
            view.doAfterTextChanged  { _ ->
                runnableWrapper[0] = debounceAndLog(handler, runnableWrapper[0], view, activity)
            }
    }

    private fun handleSeekBar(view: SeekBar, activity:Activity){
            view.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    logger.log("invoke  onProgressChanged")
                    runnableWrapper[0] = debounceAndLog(handler, runnableWrapper[0], view, activity)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })

    }

    private fun handleSpinner(view: Spinner, activity:Activity){
        view.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, childView: View, position: Int, id: Long) {
                runnableWrapper[0] = debounceAndLog(handler, runnableWrapper[0], view, activity)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

    }

    private fun handleDatePicker(view: DatePicker, activity: Activity) {
        view.setOnDateChangedListener { _, _, _, _ ->
            runnableWrapper[0] = debounceAndLog(handler, runnableWrapper[0], view, activity)
        }

    }

    private fun handleRatingBar(view: RatingBar, activity: Activity) {
        view.setOnRatingBarChangeListener { _, _, _ ->
            runnableWrapper[0] = debounceAndLog(handler, runnableWrapper[0], view, activity)
        }

    }

    private fun handleTimePicker(view: TimePicker, activity: Activity) {
        view.setOnTimeChangedListener { _, _, _ ->
            runnableWrapper[0] = debounceAndLog(handler, runnableWrapper[0], view, activity)
        }

    }

    private fun handleListView(view: ListView, activity: Activity) {
            view.setOnItemClickListener { _, childView, position, _ ->
                if (childView != null) {
                    runnableWrapper[0] = debounceAndLog(handler, runnableWrapper[0], view, activity)
                }
                else {
                    logger.error("setOnItemClickListener | Error: childView is null for ListView item at position $position")
                }
            }

            //TODO: if ListView supports focus-based selection
            view.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, childView: View?, position: Int, id: Long) {
                    if (childView != null) {
                        runnableWrapper[0] = debounceAndLog(handler, runnableWrapper[0], view, activity)
                    }
                    else {
                        logger.error("onItemSelected | Error: childView is null for ListView item selected at position $position")
                    }

                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

    }
}

