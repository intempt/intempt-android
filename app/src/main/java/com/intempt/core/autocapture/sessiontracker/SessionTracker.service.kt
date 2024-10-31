package com.intempt.core.autocapture.sessiontracker
import android.content.Context
import com.intempt.core.autocapture.BaseComponent
import com.intempt.core.eventModels.BaseIntemptEvent
import com.intempt.core.eventModels.IntemptEvent
import com.intempt.core.services.Logger
import com.intempt.core.services.StorageManagerService
import com.intempt.core.eventModels.SessionEvent
import com.intempt.core.services.eventPool.EventPoolManagerService
import com.intempt.core.services.generateId
import com.intempt.core.types.Constants
import com.intempt.core.types.DispatchEventProps
import com.intempt.core.types.IdTypeKeys
import com.intempt.core.types.StorageKeys
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
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
    private val storage: StorageManagerService,
    private val eventPool: EventPoolManagerService,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
):BaseComponent(){

    private var coroutineJob: Job? = null
    private var eventReceiverJob: Job? = null
     var ip: String = "";
    private var city: String = ""
    private var region: String = ""
    private var country: String = ""

    fun onInit(){
        val sessionTime = getSessionTime();
        val currentTimestamp = System.currentTimeMillis();


        Logger.log("IF sessionTime: $sessionTime");
        Logger.log("IF currentTimestamp: $currentTimestamp");

        if (currentTimestamp - sessionTime > Constants.SESSION.SESSION_TIMEOUT) {
            initSessionInStorage()
            runSessionStart()
        }
        else {
            Logger.log("SessionTrackerService | Session is active");
            storeSessionTime()
        }
    }

    fun subscribeToEventReceiver() {
        eventPool.subscribe(Job()) { value ->
            Logger.log("SessionTrackerService | eventReceiver $value");
            Logger.log("SessionTrackerService | getEventType ${value.getEventType()}");
            if(value.getEventType() != Constants.SESSION.EVENT_TYPE){
                validateSession(value)
            }
        }
    }

    private fun runSessionStart(){
        Logger.log("SessionTrackerService | Run session in start");
        coroutineJob?.cancel();
        coroutineJob = CoroutineScope(dispatcher).launch {
            val locationDeferred = async { getLocationInfo() }
            locationDeferred.await()
            dispatchEvent("Session start")

        }
    }

     suspend fun getLocationInfo() {
        Logger.log("SessionTrackerService | Get Location");
        withContext(dispatcher) {
            val apiUrl = Constants.SESSION.LOCATON_API;
            val client = HttpClient {
                install(ContentNegotiation) {
                    json(Json {
                        prettyPrint = true
                        isLenient = true
                    })
                }
            }
            try{
                val response: HttpResponse = client.get(apiUrl);
                val locationInfo = response.bodyAsText();

                val jsonElement = Json.parseToJsonElement(locationInfo).jsonObject

                ip = jsonElement["ip"]?.jsonPrimitive?.content ?: "";
                region = jsonElement["region"]?.jsonPrimitive?.content ?: "";
                city = jsonElement["city"]?.jsonPrimitive?.content ?: "";
                country = jsonElement["country_name"]?.jsonPrimitive?.content ?: "";
            }
            catch (e: Exception) {
                Logger.error("getLocationInfo Error: ${e.message}")
            }
            finally {
                client.close()
            }
        };
    }

    private fun initSessionInStorage() {
        Logger.log("SessionTrackerService | Initialize Session in storage");
        storeSessionId()
        storeSessionTime()
    }



    private fun dispatchEvent(sessionStartEventName:String) {

        val newEvent = SessionEvent(
            context,
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
                entityName=Constants.SESSION.ENTITY_NAME,
                type = Constants.SESSION.EVENT_TYPE,
                context = context
            )
        )

        Logger.log("SessionTrackerService | Dispatch session event: $newEvent");
    }

    private fun storeSessionId(){
        Logger.log("SessionTrackerService | Store session id");
        storage.setStorageItem(
            prefs = StorageKeys.SessionPrefs.key,
            key = StorageKeys.SessionId.key,
            value = generateId(IdTypeKeys.SessionId.key)
        ) { key, value ->
            putString(key, value)
        }
    }

    private fun storeSessionTime(){
        Logger.log("SessionTrackerService | Store session time");
        storage.setStorageItem(
            prefs = StorageKeys.SessionPrefs.key,
            key = StorageKeys.SessionTimestamp.key,
            value = System.currentTimeMillis()
        ) { key, value ->
            putLong(key, value)
        }
    }

    fun getSessionTime(): Long {
        Logger.log("SessionTrackerService | Get session timestamp");
        val fallbackTime = 0L
        return storage.getStorageItem(
            prefs = StorageKeys.SessionPrefs.key,
            key = StorageKeys.SessionTimestamp.key,
        ){ key, fallBack ->
            getLong(key,fallBack ?: fallbackTime)
        } ?: fallbackTime
    }

    //TODO: need to pass event start name
    private fun validateSession(event: IntemptEvent){
        Logger.log("SessionTrackerService | Validate session for event: $event");
        val sessionTime = getSessionTime()
        val eventTimestamp = event.getEventTimestamp()
        if (eventTimestamp - sessionTime > Constants.SESSION.SESSION_TIMEOUT) {
            initSessionInStorage()
            dispatchEvent("Test Event")
        }
    }

}