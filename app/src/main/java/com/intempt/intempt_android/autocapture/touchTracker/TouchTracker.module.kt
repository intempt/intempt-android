package com.intempt.intempt_android.autocapture.touchTracker



import com.intempt.intempt_android.configManager.ConfigManagerService
import com.intempt.intempt_android.eventPool.EventPool
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