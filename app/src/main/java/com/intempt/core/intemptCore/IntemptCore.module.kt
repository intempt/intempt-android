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
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.eventPool.EventPoolManagerService
import com.intempt.core.services.StorageManagerService
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
    fun provideIntemptService(
        storage: StorageManagerService,
        config: ConfigManagerService,
        logger: LoggerManagerService,
        eventPool: EventPoolManagerService,
        autoCaptureComponent: AutoCaptureComponent,
        customCaptureComponent: CustomCaptureComponent
    ): IntemptCoreService {
        return IntemptCoreService(
            logger,
            customCaptureComponent,
        )
    }

}


@Singleton
@Component(modules = [IntemptCoreModule::class])
internal interface IntemptCoreComponent {
    fun inject(intempt: Intempt)

    fun initService(): IntemptCoreService

    fun modification(): ModificationComponent

    @Component.Factory
    interface Factory {
        fun create(intemptModule: IntemptCoreModule): IntemptCoreComponent
    }
}

