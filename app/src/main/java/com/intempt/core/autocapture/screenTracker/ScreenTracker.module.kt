package com.intempt.core.autocapture.screenTracker

import com.intempt.core.services.eventPool.EventPool
import com.intempt.core.services.StorageService
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

@Module
internal class ScreenTrackerModule {
    @Provides
    @Singleton
    fun service(
        eventSrv: EventPool,
        storage: StorageService,
    ): ScreenTrackerService {
        return ScreenTrackerService(eventSrv, storage)
    }



    @Provides
    @Singleton
    fun component(service: ScreenTrackerService): ScreenTrackerComponent {
        return ScreenTrackerComponent(service)
    }

}