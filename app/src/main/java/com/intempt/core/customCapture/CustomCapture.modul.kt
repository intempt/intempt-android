package com.intempt.core.customCapture

import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.eventPool.EventPoolManagerService
import dagger.Module
import dagger.Provides
import javax.inject.Singleton


@Module()
internal class CustomCaptureModule {
    @Provides
    @Singleton
    fun service(): CustomCaptureService {
        return CustomCaptureService() // Return an appropriate instance here
    }

    @Provides
    @Singleton
    fun component(
        service: CustomCaptureService,
        config: ConfigManagerService,
        eventPool: EventPoolManagerService
    ): CustomCaptureComponent{
        return CustomCaptureComponent(service, config, eventPool)
    }


}