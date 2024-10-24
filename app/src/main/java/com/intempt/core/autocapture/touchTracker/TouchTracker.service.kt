package com.intempt.core.autocapture.touchTracker

import android.app.Activity
import android.os.Handler
import android.view.View
import com.intempt.core.services.Logger
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.eventPool.EventPool
import com.intempt.core.types.Constants
import com.intempt.core.types.DispatchEventProps
import com.intempt.core.services.withTryCatch
import com.intempt.core.types.UiEventProps
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class TouchTrackerService @Inject constructor(
    private val eventSrv: EventPool,
    private val config: ConfigManagerService
) {
    private fun dispatchEvent(props: UiEventProps){
        val (activity, view) = props;

        eventSrv.dispatchEvent(
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

    fun logAndDispatch(view: View?, activity: Activity, viewType: String) {
        if(view !== null){
            val errorMessage = "AutoCapture | TouchTracker Error handling $viewType view: ${view::class.simpleName}";
            withTryCatch(errorMessage) {
                Logger.log("AutoCapture | Touch for $viewType")
                dispatchEvent(
                    UiEventProps(
                        view = view,
                        activity = activity
                    )
                )
            }
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