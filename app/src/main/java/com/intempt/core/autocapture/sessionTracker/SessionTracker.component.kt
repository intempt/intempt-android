package com.intempt.core.autocapture.sessionTracker

import javax.inject.Inject
import javax.inject.Singleton


@Singleton
internal
class SessionTrackerComponent @Inject constructor(
    srv:SessionTrackerService
) {

    init{
        srv.subscribeToEventReceiver()
        srv.onInit()
    }
}