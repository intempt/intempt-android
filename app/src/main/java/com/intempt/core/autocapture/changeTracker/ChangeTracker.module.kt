package com.intempt.core.autocapture.changeTracker

import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.eventPool.EventPool
import dagger.Module
import dagger.Provides
import javax.inject.Singleton


@Module
internal class ChangeTrackerModule {
    @Provides
    @Singleton
    fun service(eventSrv: EventPool): ChangeTrackerService {
        return ChangeTrackerService(eventSrv)
    }

    @Provides
    @Singleton
    fun component(service: ChangeTrackerService): ChangeTrackerComponent {
        return ChangeTrackerComponent(service)
    }
}