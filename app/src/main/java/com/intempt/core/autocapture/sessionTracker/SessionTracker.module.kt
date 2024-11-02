package com.intempt.core.autocapture.sessionTracker
import dagger.Module
import dagger.Provides
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton


@Module
internal class SessionTrackerModule {


    @Provides
    @Singleton
    fun provideCoroutineDispatcher(): CoroutineDispatcher {
        return Dispatchers.IO
    }

    @Provides
    @Singleton
    fun component(
        srv:SessionTrackerService
    ): SessionTrackerComponent {
        return SessionTrackerComponent(srv)
    }
}
