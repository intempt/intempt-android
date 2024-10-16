package com.intempt.intempt_android.autocapture.sessiontracker
import android.content.Context
import com.intempt.intempt_android.BaseIntemptEvent
import com.intempt.intempt_android.DispatchEventProps
import com.intempt.intempt_android.EventBus
import com.intempt.intempt_android.Logger
import com.intempt.intempt_android.StorageHandler
import com.intempt.intempt_android.autocapture.eventModels.SessionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


class SessionTracker {
    companion object{
        private var debounceJob: Job? = null
        private const val DEBOUNCE_DELAY = 500L
        private const val MINUTE_STEP = 30;
        private const val SECONDS_PER_MINUTE = 60;
        private const val MILLISECONDS_PER_SECOND = 1000L;
        private const val SESSION_TIMEOUT = MINUTE_STEP * SECONDS_PER_MINUTE * MILLISECONDS_PER_SECOND


       fun start(event:BaseIntemptEvent?, context: Context){
           StorageHandler.sessionIdSet();

           val newEvent = SessionEvent(context)

           EventBus.dispatchEvent(DispatchEventProps(
               eventName = "Session start",
               event = newEvent,
               entityName="sessionStart",
               type = "session",
               context = context
           ))
       }

       fun handleSession(event:BaseIntemptEvent?, context: Context){
            if(event == null) {
                Logger.log("handleSession | Event is null")
                return;
            }
            debounceJob?.cancel()

            debounceJob = CoroutineScope(Dispatchers.Main).launch {
                delay(DEBOUNCE_DELAY)
                val eventTimestamp = event.getEventTime();
                val sessionTimestamp = StorageHandler.getSessionTime()

                if (eventTimestamp - sessionTimestamp > SESSION_TIMEOUT) {
                    Logger.log("Session expired. Creating new session.")
                    start(event,context)  // Create a new session ID
                } else {
                    Logger.log("Session active. Updating last activity timestamp.")
                    StorageHandler.updateSessionTimestamp()
                }

            }
        }



    }

}