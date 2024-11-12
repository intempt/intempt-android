package com.intempt.core.autocapture.sessionTracker
import android.content.Context
import com.intempt.core.autocapture.BaseComponent
import com.intempt.core.eventModels.IntemptEvent
import com.intempt.core.services.StorageManagerService
import com.intempt.core.services.HttpManagerService
import com.intempt.core.services.IntemptEventManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.UtilsService
import com.intempt.core.services.eventPool.EventPoolManagerService
import com.intempt.core.types.Constants
import com.intempt.core.types.DispatchEventProps
import com.intempt.core.types.IdTypeKeys
import com.intempt.core.types.StorageKeys
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class SessionTrackerService @Inject constructor(
    private val context: Context,
    private val logger: LoggerManagerService,
    private val storage: StorageManagerService,
    private val eventPool: EventPoolManagerService,
    private val http: HttpManagerService,
    private val utils: UtilsService,
    private val intemptEvent: IntemptEventManagerService,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO



):BaseComponent(logger){
    private var coroutineJob: Job? = null
    private var ip: String = "";
    private var city: String = ""
    private var region: String = ""
    private var country: String = ""




    suspend fun onInit(){
        val sessionTime = getSessionTime();
        val currentTimestamp = System.currentTimeMillis();

        if (currentTimestamp - sessionTime > Constants.SESSION.SESSION_TIMEOUT) {
            initSessionInStorage()
            runSessionStart()
        }
        else {
            logger.log("SessionTrackerService | Session is active");
            storeSessionTime()
        }
    }

    fun getSessionTime(): Long {
        logger.log("SessionTrackerService | Get session timestamp");
        val fallbackTime = 0L
        return storage.getStorageItem(
            prefs = StorageKeys.SessionPrefs.key,
            key = StorageKeys.SessionTimestamp.key,
        ){ key, fallBack ->
            getLong(key,fallBack ?: fallbackTime)
        } ?: fallbackTime
    }

    fun subscribeToEventReceiver() {
        logger.log("SessionTrackerService | Started collecting events")
        eventPool.subscribe(Job()) { value ->
            logger.log("SessionTrackerService | Received event type ${value.getEventType()}");
            if(value.getEventType() != Constants.SESSION.EVENT_TYPE){
                validateSession(value)
            }
        }
    }

    private suspend fun runSessionStart() = withContext(dispatcher) {
        logger.log("SessionTrackerService | Run session in start")
        val locationInfo = getLocationInfo() // run async location fetching if needed
        dispatchEvent("Session start")
    }

//    private fun runSessionStart(){
//        logger.log("SessionTrackerService | Run session in start");
//        coroutineJob?.cancel();
//        coroutineJob = CoroutineScope(dispatcher).launch {
//            val locationDeferred = async { getLocationInfo() }
//            locationDeferred.await()
//            dispatchEvent("Session start")
//
//        }
//    }

    suspend fun getLocationInfo() {
        logger.log("SessionTrackerService | Get Location");
        withContext(dispatcher) {
            try{
                val response: HttpResponse = http.get(Constants.SESSION.LOCATON_API);
                val locationInfo = response.bodyAsText();

                val jsonElement = Json.parseToJsonElement(locationInfo).jsonObject

                ip = jsonElement["ip"]?.jsonPrimitive?.content ?: "";
                region = jsonElement["region"]?.jsonPrimitive?.content ?: "";
                city = jsonElement["city"]?.jsonPrimitive?.content ?: "";
                country = jsonElement["country_name"]?.jsonPrimitive?.content ?: "";
            }
            catch (e: Exception) {
                logger.error("getLocationInfo Error: ${e.message}")
            }

        };
    }

    private fun initSessionInStorage() {
        logger.log("SessionTrackerService | Initialize Session in storage");
        storeSessionId()
        storeSessionTime()
    }

    private fun dispatchEvent(sessionStartEventName:String) {
        val newEvent = intemptEvent.generateSessionEventPayload(
            sessionStartEventName = sessionStartEventName,
            ipAddress = ip,
            city = city,
            region = region,
            country = country,
        )

        eventPool.dispatchEvent(
            DispatchEventProps(
                eventName = Constants.SESSION.EVENT_NAME,
                event = newEvent,
                entityName = Constants.SESSION.ENTITY_NAME,
                type = Constants.SESSION.EVENT_TYPE,
                context = context
            ),
            "SessionTrackerService"
        )

        logger.log("SessionTrackerService | Dispatch session event: $newEvent");
    }

    private fun storeSessionId(){
        logger.log("SessionTrackerService | Store session id");
        storage.setStorageItem(
            prefs = StorageKeys.SessionPrefs.key,
            key = StorageKeys.SessionId.key,
            value = utils.generateId(IdTypeKeys.SessionId.key)
        ) { key, value ->
            putString(key, value)
        }
    }

    private fun storeSessionTime(){
        logger.log("SessionTrackerService | Store session time");
        storage.setStorageItem(
            prefs = StorageKeys.SessionPrefs.key,
            key = StorageKeys.SessionTimestamp.key,
            value = System.currentTimeMillis()
        ) { key, value ->
            putLong(key, value)
        }
    }

    private fun validateSession(event: IntemptEvent){
        logger.log("SessionTrackerService | Validate session for");
        val sessionTime = getSessionTime()
        val eventTimestamp = event.getEventTimestamp()
        if (eventTimestamp - sessionTime > Constants.SESSION.SESSION_TIMEOUT) {
            initSessionInStorage()
            dispatchEvent(event.getEventName())
        }
    }

}