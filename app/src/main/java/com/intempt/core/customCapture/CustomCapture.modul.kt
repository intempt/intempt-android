package com.intempt.core.customCapture

import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.IntemptEventManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.StorageManagerService
import com.intempt.core.services.eventPool.EventPoolManagerService
import dagger.Module
import dagger.Provides
import javax.inject.Singleton


@Module()
internal class CustomCaptureModule {
    @Provides
    @Singleton
    fun component(
        service: CustomCaptureService,
        config: ConfigManagerService,
        eventPool: EventPoolManagerService,
        intemptEvent: IntemptEventManagerService,
    ): CustomCaptureComponent{
        return CustomCaptureComponent(
            service,
            config,
            eventPool,
            intemptEvent,
        )
    }


}