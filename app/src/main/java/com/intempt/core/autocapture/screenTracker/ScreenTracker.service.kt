package com.intempt.core.autocapture.screenTracker

import android.app.Activity
import androidx.fragment.app.Fragment
import com.intempt.core.services.Logger
import com.intempt.core.services.StorageService
import com.intempt.core.services.eventPool.EventPool
import com.intempt.core.types.DispatchEventProps
import com.intempt.core.services.withTryCatch
import com.intempt.core.types.ScreenEventProps
import javax.inject.Inject

internal class ScreenTrackerService @Inject constructor(
    private val eventSrv: EventPool,
    private val storage: StorageService,
) {




    fun setPageId(){
        return storage.pageIdSet()
    }


    fun handleFragmentCallbacks(callBackName:String, key:String, fragment: Fragment) {
        storage.saveFragmentName(key, fragment)

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
            dispatchEvent(
                ScreenEventProps(
                    activity = activity,
                    eventName = eventName,
                    entityName = entityName,
                    eventType = eventType
                )
            )
        }
    }

    private fun dispatchEvent(props: ScreenEventProps) {
        val (activity,eventName,entityName, eventType) = props
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