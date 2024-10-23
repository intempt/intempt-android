package com.intempt.intempt_android.autocapture.screenTracker

import com.intempt.intempt_android.eventPool.EventPool
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

@Module
internal class ScreenTrackerModule {
    @Provides
    @Singleton
    fun service(eventSrv: EventPool): ScreenTrackerService {
        return ScreenTrackerService(eventSrv)
    }



    @Provides
    @Singleton
    fun component(service: ScreenTrackerService): ScreenTrackerComponent {
        return ScreenTrackerComponent(service)
    }

}