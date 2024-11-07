package com.intempt.core.autocapture.changeTracker

import dagger.Module
import dagger.Provides
import javax.inject.Singleton


@Module
internal class ChangeTrackerModule {
    @Provides
    @Singleton
    fun component(
        service: ChangeTrackerService,
    ): ChangeTrackerComponent {
        return ChangeTrackerComponent(service)
    }
}