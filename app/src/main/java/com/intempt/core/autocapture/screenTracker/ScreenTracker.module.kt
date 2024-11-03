package com.intempt.core.autocapture.screenTracker
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

@Module
internal class ScreenTrackerModule {
    @Provides
    @Singleton
    fun component(service: ScreenTrackerService): ScreenTrackerComponent {
        return ScreenTrackerComponent(service)
    }

}