package com.intempt.core.autocapture.touchTracker



import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.eventPool.EventPool
import dagger.Module
import dagger.Provides
import javax.inject.Singleton


@Module
internal class TouchTrackerModule {

    @Provides
    @Singleton
    fun service(eventSrv: EventPool, config: ConfigManagerService): TouchTrackerService {
        return TouchTrackerService(eventSrv, config)
    }

    @Provides
    @Singleton
    fun component(
        service: TouchTrackerService,
    ): TouchTrackerComponent {
        return TouchTrackerComponent(service)
    }
}