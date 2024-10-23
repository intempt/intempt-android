package com.intempt.intempt_android.autocapture.touchTracker

import android.app.Activity
import android.os.Handler
import android.view.View
import com.intempt.intempt_android.Logger
import com.intempt.intempt_android.configManager.ConfigManagerService
import com.intempt.intempt_android.eventPool.EventPool
import com.intempt.intempt_android.types.Constants
import com.intempt.intempt_android.types.DispatchEventProps
import com.intempt.intempt_android.withTryCatch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class TouchTrackerService @Inject constructor(
    private val eventSrv: EventPool,
    private val config: ConfigManagerService
) {
    private fun dispatchEvent(view: View?, activity: Activity){
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
                dispatchEvent(view, activity)
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