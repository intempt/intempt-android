package com.intempt.core.autocapture.sessionTracker

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class SessionTrackerComponent
    @Inject
    constructor(
        private val srv: SessionTrackerService,
    ) {
        fun start() {
            srv.enableSessionEvents()
        }
    }
