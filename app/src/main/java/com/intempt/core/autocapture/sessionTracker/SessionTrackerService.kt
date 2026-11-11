@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core.autocapture.sessionTracker
import android.content.Context
import com.intempt.core.autocapture.BaseComponent
import com.intempt.core.services.IntemptEventManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.StorageManagerService
import com.intempt.core.services.eventPool.EventPoolManagerService
import com.intempt.core.types.Constants
import com.intempt.core.types.DispatchEventProps
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
        private val intemptEvent: IntemptEventManagerService,
    ) : BaseComponent(logger) {
        fun enableSessionEvents() {
            storage.onSessionStart = { dispatchEvent("Session start") }
        }

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
    }
