package com.intempt.core.autocapture.lifecycleCallbacksTracker
import android.R
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.text.InputType
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
internal open class ChangeTrackerService
    @Inject
    constructor(
        private val eventPool: EventPoolManagerService,
        val logger: LoggerManagerService,
        private val utils: UtilsService,
    ) {
        private companion object {
            /** Stands in for a secret. A fixed string, not derived from the value or its
             *  length, so nothing about the contents leaks. */
            const val MASKED_VALUE = "[masked]"
        }

        private val previousStateMap = mutableMapOf<Int, Any?>()
        private val debounceDelay = Constants.DEBOUNCE_DELAY
        private val handler = Handler(Looper.getMainLooper())
        private val runnableWrapper: Array<Runnable?> = arrayOfNulls(1)

        fun registerListener(activity: Activity)  {
            (activity.findViewById(R.id.content) as? ViewGroup)?.let { rootView ->
                findAndRegisterChangeEvent(rootView, activity)
            }
        }

        private fun findAndRegisterChangeEvent(
            viewGroup: ViewGroup,
            activity: Activity,
        )  {
            logger.log("Invoke findAndInvokeChangeEvent")
            for (i in 0 until viewGroup.childCount) {
                when (val view = viewGroup.getChildAt(i)) {
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

        private fun checkViews(view: View): Boolean  {
            return view is EditText ||
                view is Spinner ||
                view is ToggleButton ||
                view is CheckBox ||
                view is RadioButton ||
                view is CompoundButton ||
                view is TextView ||
                view is SeekBar ||
                view is RatingBar ||
                view is TimePicker ||
                view is DatePicker ||
                view is ListView
        }

        /**
         * True when this field holds a secret and its contents must never be captured.
         *
         * Covers every password variant Android defines, including the number-pad PIN
         * variant. `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD` is included deliberately: the user
         * having chosen to reveal it on screen does not make it safe to send off-device.
         */
        private fun isSensitiveInput(view: EditText): Boolean {
            val variation = view.inputType and InputType.TYPE_MASK_VARIATION
            val cls = view.inputType and InputType.TYPE_MASK_CLASS
            return when {
                cls == InputType.TYPE_CLASS_TEXT && variation == InputType.TYPE_TEXT_VARIATION_PASSWORD -> true
                cls == InputType.TYPE_CLASS_TEXT && variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD -> true
                cls == InputType.TYPE_CLASS_TEXT && variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> true
                cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD -> true
                else -> false
            }
        }

        private fun getViewValue(view: View): Any? {
            return when (view) {
                is CompoundButton -> view.isChecked
                // Autocapture used to send raw EditText contents for every field, password
                // inputs included, so a host app's login screen exfiltrated credentials
                // verbatim with no way to opt out short of disabling autocapture entirely.
                // Sensitive fields now report only that they changed, never what to.
                is EditText -> if (isSensitiveInput(view)) MASKED_VALUE else view.text.toString()
                is Spinner -> view.selectedItem
                is SeekBar -> view.progress
                is RatingBar -> view.rating
                is DatePicker -> "${view.year}-${view.month}-${view.dayOfMonth}"
                // getHour/getMinute are API 23, which is now minSdk, so no guard is needed.
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
            val errorMessage = "AutoCapture | ChangeTracker Error handling"
            return utils.debounce(handler, debounceDelay, currentRunnable) {
                utils.withTryCatch(errorMessage) {
                    eventPool.dispatchEvent(
                        DispatchEventProps(
                            eventName = Constants.CHANGE.EVENT_NAME,
                            entityName = Constants.CHANGE.ENTITY_NAME,
                            type = Constants.CHANGE.EVENT_TYPE,
                            context = activity,
                            view = view,
                        ),
                        "ChangeTrackerService",
                    )
                }
            }
        }

        private fun handleChangeListenerRegistration(
            view: View,
            activity: Activity,
        ) {
            logger.log("ChangeTrackerService | Start registration for ${view.javaClass.name}")
            val errorMessage = "ChangeTrackerService | ChangeTracker Error. Handling view: ${view::class.simpleName}"
            utils.withTryCatch(errorMessage) {
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

        private fun handleCompoundButton(
            view: CompoundButton,
            activity: Activity,
        )  {
            logger.log("ChangeTrackerService | Perform CompoundButton registration for $${view.javaClass.name}")

            view.setOnCheckedChangeListener { _, isChecked ->
                logger.log("ChangeTrackerService | Listener triggered for ${view.javaClass.name} with state $isChecked")

                runnableWrapper[0] = debounceAndLog(handler, runnableWrapper[0], view, activity)
            }
        }

        private fun handleRadioButton(
            view: CompoundButton,
            activity: Activity,
        )  {
            view.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    runnableWrapper[0] = debounceAndLog(handler, runnableWrapper[0], view, activity)
                }
            }
        }

        private fun handleEditText(
            view: EditText,
            activity: Activity,
        )  {
            view.doAfterTextChanged { _ ->
                runnableWrapper[0] = debounceAndLog(handler, runnableWrapper[0], view, activity)
            }
        }

        private fun handleSeekBar(
            view: SeekBar,
            activity: Activity,
        )  {
            view.setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar?,
                        progress: Int,
                        fromUser: Boolean,
                    ) {
                        logger.log("invoke  onProgressChanged")
                        runnableWrapper[0] = debounceAndLog(handler, runnableWrapper[0], view, activity)
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                },
            )
        }

        private fun handleSpinner(
            view: Spinner,
            activity: Activity,
        )  {
            view.onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?,
                        childView: View,
                        position: Int,
                        id: Long,
                    ) {
                        runnableWrapper[0] = debounceAndLog(handler, runnableWrapper[0], view, activity)
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
        }

        private fun handleDatePicker(
            view: DatePicker,
            activity: Activity,
        ) {
            // init() rather than setOnDateChangedListener(): the latter is API 26, and lint
            // caught it crashing below that. init() takes the same listener and has existed
            // since API 1, so it works at every level the SDK supports.
            view.init(view.year, view.month, view.dayOfMonth) { _, _, _, _ ->
                runnableWrapper[0] = debounceAndLog(handler, runnableWrapper[0], view, activity)
            }
        }

        private fun handleRatingBar(
            view: RatingBar,
            activity: Activity,
        ) {
            view.setOnRatingBarChangeListener { _, _, _ ->
                runnableWrapper[0] = debounceAndLog(handler, runnableWrapper[0], view, activity)
            }
        }

        private fun handleTimePicker(
            view: TimePicker,
            activity: Activity,
        ) {
            view.setOnTimeChangedListener { _, _, _ ->
                runnableWrapper[0] = debounceAndLog(handler, runnableWrapper[0], view, activity)
            }
        }

        private fun handleListView(
            view: ListView,
            activity: Activity,
        ) {
            view.setOnItemClickListener { _, childView, position, _ ->
                if (childView != null) {
                    runnableWrapper[0] = debounceAndLog(handler, runnableWrapper[0], view, activity)
                } else {
                    logger.error("setOnItemClickListener | Error: childView is null for ListView item at position $position")
                }
            }

            // TODO: if ListView supports focus-based selection
            view.onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?,
                        childView: View?,
                        position: Int,
                        id: Long,
                    ) {
                        if (childView != null) {
                            runnableWrapper[0] = debounceAndLog(handler, runnableWrapper[0], view, activity)
                        } else {
                            logger.error("onItemSelected | Error: childView is null for ListView item selected at position $position")
                        }
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
        }
    }
