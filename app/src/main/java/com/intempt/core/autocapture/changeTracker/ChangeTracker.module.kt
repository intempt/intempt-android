package com.intempt.core.autocapture.changeTracker


import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.UtilsService
import dagger.Module
import dagger.Provides
import javax.inject.Singleton


@Module
internal class ChangeTrackerModule {
//    @Provides
//    @Singleton
//    fun service(
//       // eventSrv: EventPool
//        logger: LoggerManagerService,
//        utils: UtilsService
//    ): ChangeTrackerService {
//       // return ChangeTrackerService(eventSrv)
//        return ChangeTrackerService(logger,utils)
//    }

    @Provides
    @Singleton
    fun component(
        service: ChangeTrackerService,
        logger: LoggerManagerService
    ): ChangeTrackerComponent {
        return ChangeTrackerComponent(service, logger)
    }
}