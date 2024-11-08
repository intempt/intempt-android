package com.intempt.core.intemptCore

import android.content.Context
import com.intempt.core.Intempt
import com.intempt.core.autocapture.AutoCaptureComponent
import com.intempt.core.autocapture.AutoCaptureModule
import com.intempt.core.customCapture.CustomCaptureComponent
import com.intempt.core.customCapture.CustomCaptureModule
import com.intempt.core.modifications.ModificationComponent
import com.intempt.core.modifications.ModificationsModule
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.HttpManagerService
import com.intempt.core.services.IntemptEventManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.eventPool.EventPoolManagerService
import com.intempt.core.services.StorageManagerService
import com.intempt.core.services.UtilsService
import dagger.Component
import dagger.Module
import dagger.Provides
import javax.inject.Singleton



@Module(includes = [
    AutoCaptureModule::class,
    CustomCaptureModule::class,
    ModificationsModule::class
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
    fun provideUtilsService(
        logger: LoggerManagerService
    ): UtilsService{
        return UtilsService(logger)
    }

    @Provides
    @Singleton
    fun provideHttpService(
        config: ConfigManagerService,
        logger: LoggerManagerService
    ): HttpManagerService{
        return HttpManagerService(config, logger)
    }

    @Provides
    @Singleton
    fun provideLoggerService(
        config: ConfigManagerService,
    ): LoggerManagerService{
        return LoggerManagerService(config)
    }

    @Provides
    @Singleton
    fun provideStorageManagerService(): StorageManagerService{
        return StorageManagerService(consumerContext.applicationContext)
    }

    @Provides
    @Singleton
    fun provideEventPoolManagerService(
        config: ConfigManagerService,
        logger: LoggerManagerService,
        http: HttpManagerService,
        intemptEvent: IntemptEventManagerService,
    ): EventPoolManagerService{
        return EventPoolManagerService(
            config,
            logger,
            http,
            intemptEvent
        )
    }
    @Provides
    @Singleton
    fun provideIntemptEventManagerService(
        storage: StorageManagerService,
        utils: UtilsService,
        config: ConfigManagerService,
    ):IntemptEventManagerService{
        return IntemptEventManagerService(
            consumerContext.applicationContext,
            storage,
            utils,
            config
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

