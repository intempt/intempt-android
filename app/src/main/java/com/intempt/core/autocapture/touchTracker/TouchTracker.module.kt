package com.intempt.core.autocapture.touchTracker

import dagger.Module
import dagger.Provides
import javax.inject.Singleton


@Module
internal class TouchTrackerModule {
    @Provides
    @Singleton
    fun component(
        service: TouchTrackerService,
    ): TouchTrackerComponent {
        return TouchTrackerComponent(service)
    }
}