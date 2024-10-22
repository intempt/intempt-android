package com.intempt.intempt_android.autocapture.changeTracker

import com.intempt.intempt_android.eventPool.EventPool
import dagger.Module
import dagger.Provides
import javax.inject.Singleton


@Module
internal class ChangeTrackerModule {
    @Provides
    @Singleton
    fun provideChangeTrackerService(eventSrv: EventPool): ChangeTrackerService {
        return ChangeTrackerService(eventSrv)
    }

    @Provides
    @Singleton
    fun provideChangeTracker(service: ChangeTrackerService): ChangeTrackerComponent {
        return ChangeTrackerComponent(service)
    }
}