package com.intempt.core.intemptCore

import android.content.Context
import com.intempt.core.Intempt
import com.intempt.core.autocapture.AutoCaptureComponent
import com.intempt.core.autocapture.AutoCaptureModule
import com.intempt.core.autocapture.sessiontracker.SessionTrackerService
//import com.intempt.intempt_android.configManager.ConfigManagerModule
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.eventPool.EventPool
//import com.intempt.intempt_android.storage.StorageModule
import com.intempt.core.services.StorageService
import dagger.Component
import dagger.Module
import dagger.Provides
import javax.inject.Singleton



@Module(includes = [
//    StorageModule::class,
   // ConfigManagerModule::class,
    AutoCaptureModule::class
])
internal class IntemptCoreModule(
    private val consumerContext: Context
) {

    @Provides
    fun provideContext(): Context {
        return consumerContext.applicationContext
    }

    @Provides
    @Singleton
    fun provideEventPool(srv: SessionTrackerService): EventPool {
        return EventPool(srv)
    }


    @Provides
    @Singleton
    fun provideStorageService(): StorageService {
        return StorageService(consumerContext)
    }

    @Provides
    @Singleton
    fun provideConfigManagerService(): ConfigManagerService {
        return ConfigManagerService(consumerContext)
    }


    @Provides
    @Singleton
    fun provideIntemptService(
        eventPool: EventPool,
        //storageService: StorageService,
        configManagerService: ConfigManagerService,
        autoCaptureComponent: AutoCaptureComponent
    ): IntemptCoreService {
        return IntemptCoreService(
            eventPool,
           // storageService,
            configManagerService,
            autoCaptureComponent
        )
    }

}


@Singleton
@Component(modules = [IntemptCoreModule::class])
internal interface IntemptCoreComponent {
    fun inject(intempt: Intempt)

    fun initService(): IntemptCoreService

    @Component.Factory
    interface Factory {
        fun create(intemptModule: IntemptCoreModule): IntemptCoreComponent
    }
}