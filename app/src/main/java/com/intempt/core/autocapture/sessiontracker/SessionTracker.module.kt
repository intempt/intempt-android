package com.intempt.core.autocapture.sessiontracker
import android.content.Context
import com.intempt.core.services.StorageService
import dagger.Module
import dagger.Provides
import javax.inject.Singleton


@Module
internal class SessionTrackerModule {
    @Provides
    @Singleton
    fun service(
        context: Context,
        storage: StorageService,
    ): SessionTrackerService {
        return SessionTrackerService(context, storage)
    }
}