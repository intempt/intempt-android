package com.intempt.core.autocapture.touchTracker



import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.LoggerManagerService
import dagger.Module
import dagger.Provides
import javax.inject.Singleton


@Module
internal class TouchTrackerModule {

//    @Provides
//    @Singleton
//    fun service(
//       // eventSrv: EventPool,
//        config: ConfigManagerService,
//        logger: LoggerManagerService
//    ): TouchTrackerService {
//        return TouchTrackerService(logger, config)
////        return TouchTrackerService(logger)
//    }

    @Provides
    @Singleton
    fun component(
        service: TouchTrackerService,
    ): TouchTrackerComponent {
        return TouchTrackerComponent(service)
    }
}