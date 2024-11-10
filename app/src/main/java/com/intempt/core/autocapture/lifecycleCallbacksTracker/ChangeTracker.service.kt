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
import android.widget.Switch
import android.widget.TextView
import android.widget.TimePicker
import android.widget.ToggleButton
import androidx.appcompat.widget.SwitchCompat
import androidx.compose.ui.platform.ComposeView
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.RecyclerView
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

    private lateinit var observer: ViewTreeObserver.OnGlobalLayoutListener
    private var isObserving = false

    private val previousStateMap = mutableMapOf<Int, Any?>()
    private val debounceDelay = Constants.DEBOUNCE_DELAY
    private val handler = Handler(Looper.getMainLooper())
    private val runnableWrapper: Array<Runnable?> = arrayOfNulls(1)


    fun unregisterListener(activity: Activity){
        isObserving = false
        activity.window.decorView.viewTreeObserver.removeOnGlobalLayoutListener(observer)
    }

    fun registerListener(activity: Activity){
        observer = ViewTreeObserver.OnGlobalLayoutListener {
            val rootView = activity.window.decorView.findViewById<ViewGroup>(R.id.content)
            rootView?.let { view ->
                    findAndInvokeChangeEvent(view, activity)
            }
           // unregisterListener(activity)
        }
        activity.window.decorView.viewTreeObserver.addOnGlobalLayoutListener(observer)
    }

    private fun findAndInvokeChangeEvent(viewGroup: ViewGroup, activity: Activity){
        logger.log("Invoke findAndInvokeChangeEvent")
        for (i in 0 until viewGroup.childCount) {
            when( val view = viewGroup.getChildAt(i)){
                is RecyclerView -> {
                    val layoutManager = view.layoutManager
                    for (j in 0 until view.childCount) {
                        val childView = layoutManager?.getChildAt(i)
                        if (childView != null) {
                            findAndInvokeChangeEvent(view, activity)
                        }
                    }
                }
                is ViewGroup -> findAndInvokeChangeEvent(view, activity)
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


    private fun handleChangeListenerRegistration(view: View, activity:Activity) {
        logger.log("Invoke handleChangeListenerRegistration")
        val errorMessage = "ChangeTrackerService | ChangeTracker Error. Handling view: ${view::class.simpleName}"
        utils.withTryCatch(errorMessage){
            val canUse = checkViews(view)
            val hash = view.hashCode()

            if(canUse){
                val currentValue  = getViewValue(view)
                val previousValue = previousStateMap[hash]
                if (previousValue == null || previousValue != currentValue) {
                    previousStateMap[hash] = currentValue

                    runnableWrapper[0] = debounceAndLog(handler, runnableWrapper[0], view, activity)
                }
            }
            else{
                logger.log("ChangeTrackerService | Couldn't register change event for ${view.javaClass.name}")
            }
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
}

