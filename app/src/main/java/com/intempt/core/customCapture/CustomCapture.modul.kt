package com.intempt.core.customCapture

import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.eventPool.EventPoolManagerService
import dagger.Module
import dagger.Provides
import javax.inject.Singleton


@Module()
internal class CustomCaptureModule {
    @Provides
    @Singleton
    fun service(
        logger: LoggerManagerService
    ): CustomCaptureService {
        return CustomCaptureService(logger)
    }

    @Provides
    @Singleton
    fun component(
        service: CustomCaptureService,
        config: ConfigManagerService,
        eventPool: EventPoolManagerService,
        logger: LoggerManagerService
    ): CustomCaptureComponent{
        return CustomCaptureComponent(service, config, eventPool, logger)
    }


}