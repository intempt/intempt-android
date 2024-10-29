package com.intempt.core.autocapture.screenTracker

import com.intempt.core.services.StorageManagerService
import com.intempt.core.services.eventPool.EventPoolManagerService
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

@Module
internal class ScreenTrackerModule {
    @Provides
    @Singleton
    fun service(
        eventSrv: EventPoolManagerService,
        storage: StorageManagerService,
    ): ScreenTrackerService {
//        return ScreenTrackerService(eventSrv, storage)
        return ScreenTrackerService(eventSrv)
    }



    @Provides
    @Singleton
    fun component(service: ScreenTrackerService): ScreenTrackerComponent {
        return ScreenTrackerComponent(service)
    }

}