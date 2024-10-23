package com.intempt.intempt_android.autocapture.screenTracker

import android.app.Activity
import androidx.fragment.app.Fragment
import com.intempt.intempt_android.Logger
import com.intempt.intempt_android.StorageHandler
import com.intempt.intempt_android.eventPool.EventPool
import com.intempt.intempt_android.types.Constants
import com.intempt.intempt_android.types.DispatchEventProps
import com.intempt.intempt_android.withTryCatch
import javax.inject.Inject

internal class ScreenTrackerService @Inject constructor(
    private val eventSrv: EventPool
) {

    fun handleFragmentCallbacks(callBackName:String, key:String, fragment: Fragment) {
        StorageHandler.saveFragmentName(
            key,
            fragment,
        )

        Logger.log("AutoCapture | $callBackName: ${fragment::class.java.simpleName}")
    }

    fun logAndDispatch(
        activity: Activity,
        eventName:String,
        entityName:String,
        eventType:String,
        viewType: String,

    ) {
        val errorMessage = "AutoCapture | ScreenTracker $viewType Error handling: ${activity.localClassName}";
        withTryCatch(errorMessage) {
            Logger.log("AutoCapture | ${eventName}: ${activity.localClassName}")
            dispatchEvent(activity, eventName, entityName, eventType)
        }
    }

    private fun dispatchEvent(
        activity: Activity,
        eventName:String,
        entityName:String,
        eventType:String,
    ) {
        eventSrv.dispatchEvent(
            DispatchEventProps(
                eventName = eventName,
                entityName = entityName,
                type = eventType ,
                event = null,
                context = activity
            )
        )
    }
}