@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core.autocapture.sessionTracker
import android.content.Context
import com.intempt.core.autocapture.BaseComponent
import com.intempt.core.eventModels.IntemptEvent
import com.intempt.core.services.HttpManagerService
import com.intempt.core.services.IntemptEventManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.StorageManagerService
import com.intempt.core.services.UtilsService
import com.intempt.core.services.eventPool.EventPoolManagerService
import com.intempt.core.types.Constants
import com.intempt.core.types.DispatchEventProps
import com.intempt.core.types.IdTypeKeys
import com.intempt.core.types.StorageKeys
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class SessionTrackerService
    @Inject
    constructor(
        private val context: Context,
        private val logger: LoggerManagerService,
        private val storage: StorageManagerService,
        private val eventPool: EventPoolManagerService,
        private val http: HttpManagerService,
        private val utils: UtilsService,
        private val intemptEvent: IntemptEventManagerService,
        private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : BaseComponent(logger) {
        private var coroutineJob: Job? = null

        suspend fun onInit() {
            val sessionTime = getSessionTime()
            val currentTimestamp = System.currentTimeMillis()

            if (currentTimestamp - sessionTime > Constants.SESSION.SESSION_TIMEOUT) {
                initSessionInStorage()
                runSessionStart()
            } else {
                logger.log("SessionTrackerService | Session is active")
                storeSessionTime()
            }
        }

        fun getSessionTime(): Long {
            logger.log("SessionTrackerService | Get session timestamp")
            val fallbackTime = 0L
            return storage.getStorageItem(
                prefs = StorageKeys.SessionPrefs.key,
                key = StorageKeys.SessionTimestamp.key,
            ) { key, fallBack ->
                getLong(key, fallBack ?: fallbackTime)
            } ?: fallbackTime
        }

        fun subscribeToEventReceiver() {
            logger.log("SessionTrackerService | Started collecting events")
            eventPool.subscribe(Job()) { value ->
                logger.log("SessionTrackerService | Received event type ${value.getEventType()}")
                if (value.getEventType() != Constants.SESSION.EVENT_TYPE) {
                    validateSession(value)
                }
            }
        }

        private suspend fun runSessionStart() =
            withContext(dispatcher) {
                logger.log("SessionTrackerService | Run session in start")
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

        // getLocationInfo() used to live here. It called ipapi.co on every session start, read back
        // the device's public IP plus city/region/country, and attached all four to the payload.
        //
        // Removed, matching mixpanel-android. Three separate problems, not one:
        //
        //   - It sent the device's IP to a third party on every session start. Mixpanel makes no
        //     third-party call at all; its server derives geo from the source IP of the request it
        //     already receives (MPConfig.getEndPointWithIpTrackingParam appends ?ip=1, one query
        //     param, and that is the entire mechanism).
        //   - It ran regardless of consent. Every other capture path in this SDK checks
        //     config.isUserOptIn; this one uniquely did not, so an opted-out user's device still
        //     called out.
        //   - There was no consumer switch and no sub-processor disclosure. Mixpanel ships
        //     setUseIpAddressForGeolocation(boolean) plus a manifest key, defaulting to on.
        //
        // Geo is now server-derived: the ?ip= parameter on the events endpoint tells the platform
        // whether to geolocate from the request. See ConfigManagerService.eventsUrl.
        //
        // NOTE FOR THE PLATFORM: this depends on ingestion honouring ?ip= and deriving
        // city/region/country from the request IP. Until that ships, session events carry no geo.
        // Tracked separately from this SDK change.

        private fun initSessionInStorage() {
            logger.log("SessionTrackerService | Initialize Session in storage")
            storeSessionId()
            storeSessionTime()
        }

        private fun dispatchEvent(sessionStartEventName: String) {
            val newEvent =
                intemptEvent.generateSessionEventPayload(
                    sessionStartEventName = sessionStartEventName,
                )

            eventPool.dispatchEvent(
                DispatchEventProps(
                    eventName = Constants.SESSION.EVENT_NAME,
                    event = newEvent,
                    entityName = Constants.SESSION.ENTITY_NAME,
                    type = Constants.SESSION.EVENT_TYPE,
                    context = context,
                ),
                "SessionTrackerService",
            )

            logger.log("SessionTrackerService | Dispatch session event: $newEvent")
        }

        private fun storeSessionId() {
            logger.log("SessionTrackerService | Store session id")
            storage.setStorageItem(
                prefs = StorageKeys.SessionPrefs.key,
                key = StorageKeys.SessionId.key,
                value = utils.generateId(IdTypeKeys.SessionId.key),
            ) { key, value ->
                putString(key, value)
            }
        }

        private fun storeSessionTime() {
            logger.log("SessionTrackerService | Store session time")
            storage.setStorageItem(
                prefs = StorageKeys.SessionPrefs.key,
                key = StorageKeys.SessionTimestamp.key,
                value = System.currentTimeMillis(),
            ) { key, value ->
                putLong(key, value)
            }
        }

        private fun validateSession(event: IntemptEvent) {
            logger.log("SessionTrackerService | Validate session for")
            val sessionTime = getSessionTime()
            val eventTimestamp = event.getEventTimestamp()
            if (eventTimestamp - sessionTime > Constants.SESSION.SESSION_TIMEOUT) {
                initSessionInStorage()
                dispatchEvent(event.getEventName())
            }
        }
    }
