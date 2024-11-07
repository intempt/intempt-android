package com.intempt.core.autocapture.touchTracker

import android.app.Activity
import android.os.Handler
import android.view.View
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.UtilsService
import com.intempt.core.services.eventPool.EventPoolManagerService
import com.intempt.core.types.Constants
import com.intempt.core.types.DispatchEventProps
import com.intempt.core.types.UiEventProps
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class TouchTrackerService @Inject constructor(
    private val logger: LoggerManagerService,
    private val eventPool: EventPoolManagerService,
    private val config: ConfigManagerService,
    private val utils: UtilsService
) {

    val isTouchEnabled: Boolean get() = config.isTouchEnabled;
    private val debounceDelay = Constants.DEBOUNCE_DELAY

    private fun dispatchEvent(props: UiEventProps){
        if(!isTouchEnabled) return;
        val (activity, view) = props;

        eventPool.dispatchEvent(
            DispatchEventProps(
                eventName = Constants.TOUCH.EVENT_NAME,
                entityName = Constants.TOUCH.ENTITY_NAME,
                type = Constants.TOUCH.EVENT_TYPE,
                event = null,
                context = activity,
                view = view
            )
        )
    }

    private fun logAndDispatch(view: View?, activity: Activity, viewType: String) {
        if(view !== null){
            val errorMessage = "AutoCapture | TouchTracker Error handling $viewType view: ${view::class.simpleName}";
            utils.withTryCatch(errorMessage) {
                logger.log("AutoCapture | Touch for $viewType")
                dispatchEvent(
                    UiEventProps(
                        view = view,
                        activity = activity,
                        listenerType = "touch"
                    )
                )
            }
        }
    }

    fun debounceAndLog(
        handler: Handler,
        currentRunnable: Runnable?,
        view: View?,
        activity: Activity,
        viewType: String,
        onDebouncedAction: (() -> Unit)? = null
    ): Runnable {
        return utils.debounce(handler, debounceDelay, currentRunnable) {
            logAndDispatch(view, activity, viewType)
            onDebouncedAction?.invoke()
        }
    }
    fun setupHandler(
        handler: Handler,
        runnableWrapper: Array<Runnable?>,
        eventListener: (Handler, Array<Runnable?>) -> Unit
    ) {
        eventListener(handler, runnableWrapper)
    }
}