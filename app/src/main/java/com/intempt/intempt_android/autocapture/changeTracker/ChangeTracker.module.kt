package com.intempt.intempt_android.autocapture.changeTracker

import com.intempt.intempt_android.configManager.ConfigManagerService
import com.intempt.intempt_android.eventPool.EventPool
import dagger.Module
import dagger.Provides
import javax.inject.Singleton


@Module
internal class ChangeTrackerModule {
    @Provides
    @Singleton
    fun service(eventSrv: EventPool, config: ConfigManagerService): ChangeTrackerService {
        return ChangeTrackerService(eventSrv, config)
    }

    @Provides
    @Singleton
    fun component(service: ChangeTrackerService): ChangeTrackerComponent {
        return ChangeTrackerComponent(service)
    }
}