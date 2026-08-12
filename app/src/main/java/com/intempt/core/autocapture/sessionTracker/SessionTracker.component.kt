package com.intempt.core.autocapture.sessionTracker

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class SessionTrackerComponent
    @Inject
    constructor(
        private val srv: SessionTrackerService,
    ) {
        suspend fun start()  {
            srv.subscribeToEventReceiver()
            srv.onInit()
        }
    }
