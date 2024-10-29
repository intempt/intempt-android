package com.intempt.core.autocapture.sessiontracker

import javax.inject.Inject
import javax.inject.Singleton


@Singleton
internal
class SessionTrackerComponent @Inject constructor(
    private val srv:SessionTrackerService
) {

    init{
        srv.subscribeToEventReceiver()
        srv.onInit()
    }
}