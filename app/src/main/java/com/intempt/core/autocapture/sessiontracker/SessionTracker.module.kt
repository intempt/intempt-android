package com.intempt.core.autocapture.sessiontracker
import android.content.Context
import com.intempt.core.services.StorageManagerService
import com.intempt.core.services.eventPool.EventPoolManagerService
import dagger.Module
import dagger.Provides
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton


@Module
internal class SessionTrackerModule {
//    @Provides
//    @Singleton
//    fun service(
//        context: Context,
//        storage: StorageManagerService,
//        eventPool: EventPoolManagerService
//    ): SessionTrackerService {
//        return SessionTrackerService(context, storage, eventPool)
//    }

    @Provides
    @Singleton
    fun provideCoroutineDispatcher(): CoroutineDispatcher {
        return Dispatchers.IO // or Dispatchers.Default, Dispatchers.Main, etc.
    }

    @Provides
    @Singleton
    fun component(
        srv:SessionTrackerService
    ): SessionTrackerComponent {
        return SessionTrackerComponent(srv)
    }
}
