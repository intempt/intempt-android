package com.intempt.intempt_android
import android.content.Context
import com.intempt.intempt_android.autocapture.sessiontracker.SessionTracker



data class DispatchEventProps(val eventName:String,val event:BaseIntemptEvent? , val type:String,  val context: Context)

object EventBus {
    fun dispatchEvent(props:DispatchEventProps) {
        val (eventName, event, type, context) = props

        Logger.log("Received Event: $eventName")

        SessionTracker.handleSession(event, context)
    }

}