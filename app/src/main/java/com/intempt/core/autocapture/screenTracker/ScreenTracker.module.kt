package com.intempt.core.autocapture.screenTracker

import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.StorageManagerService
import com.intempt.core.services.UtilsService
import com.intempt.core.services.eventPool.EventPoolManagerService
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

@Module
internal class ScreenTrackerModule {
//    @Provides
//    @Singleton
//    fun service(
//        eventSrv: EventPoolManagerService,
//        storage: StorageManagerService,
//        logger: LoggerManagerService,
//        utils: Utils
//    ): ScreenTrackerService {
////        return ScreenTrackerService(eventSrv, storage)
//        return ScreenTrackerService(eventSrv, logger, utils)
//    }



    @Provides
    @Singleton
    fun component(service: ScreenTrackerService): ScreenTrackerComponent {
        return ScreenTrackerComponent(service)
    }

}