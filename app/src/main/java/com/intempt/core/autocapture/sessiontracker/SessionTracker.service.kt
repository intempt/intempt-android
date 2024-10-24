package com.intempt.core.autocapture.sessiontracker
import android.content.Context
import com.intempt.core.autocapture.BaseComponent
import com.intempt.core.eventModels.BaseIntemptEvent
import com.intempt.core.services.Logger
import com.intempt.core.services.StorageService
import com.intempt.core.eventModels.SessionEvent
import com.intempt.core.eventModels.SessionUserAttributes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject


internal class SessionTrackerService @Inject constructor(
    private val context: Context,
    private val storage: StorageService
):BaseComponent(){

    private var debounceJob: Job? = null
    private  val DEBOUNCE_DELAY = 500L
    private  val MINUTE_STEP = 30;
    private  val SECONDS_PER_MINUTE = 60;
    private  val MILLISECONDS_PER_SECOND = 1000L;
    private  val SESSION_TIMEOUT = MINUTE_STEP * SECONDS_PER_MINUTE * MILLISECONDS_PER_SECOND


    private fun start(event: BaseIntemptEvent?){
        storage.sessionIdSet();

        val newEvent = SessionEvent(context)

//           EventBus.dispatchEvent(DispatchEventProps(
//               eventName = "Session start",
//               event = newEvent,
//               entityName="sessionStart",
//               type = "session",
//               context = context
//           ))
    }

    fun handleSession(event: BaseIntemptEvent?){
        if(event == null) {
            Logger.log("handleSession | Event is null")
            return;
        }
        debounceJob?.cancel()

        debounceJob = CoroutineScope(Dispatchers.Main).launch {
            delay(DEBOUNCE_DELAY)
            val eventTimestamp = event.getEventTime();
            val sessionTimestamp = storage.getSessionTime()

            if (eventTimestamp - sessionTimestamp > SESSION_TIMEOUT) {
                Logger.log("Session expired. Creating new session.")
                start(event)
            } else {
                Logger.log("Session active. Updating last activity timestamp.")
                storage.updateSessionTimestamp()
            }

        }
    }

    private fun initLocationInfo(){
        val job = Job();
        val coroutineScope = CoroutineScope(Dispatchers.Main + job)
        coroutineScope.launch {
            val locationDeferred = async { SessionUserAttributes.getLocationInfo() }
            locationDeferred.await()
        }
    }

}