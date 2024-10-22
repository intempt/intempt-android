package com.intempt.intempt_android.autocapture.changeTracker

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import com.intempt.intempt_android.Constants
import com.intempt.intempt_android.Logger
import com.intempt.intempt_android.eventPool.EventPool
import com.intempt.intempt_android.types.DispatchEventProps
import com.intempt.intempt_android.withTryCatch
import javax.inject.Inject



internal class ChangeTrackerService @Inject constructor(
    private val eventSrv: EventPool
) {

    fun logAndDispatchChange(view: View, activity: Activity, viewType: String) {
        val errorMessage = "AutoCapture | ChangeTracker Error handling $viewType view: ${view::class.simpleName}";
        withTryCatch(errorMessage) {
            Logger.log("AutoCapture | Change for $viewType")
            dispatchChangeEvent(view, activity)
        }
    }

    fun setupHandler(
        eventListener: (Handler, Array<Runnable?>) -> Unit
    ) {
        val handler = Handler(Looper.getMainLooper())
        val runnableWrapper: Array<Runnable?> = arrayOfNulls(1)
        eventListener(handler, runnableWrapper)
    }

    private fun dispatchChangeEvent(view: View, activity: Activity) {
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
}