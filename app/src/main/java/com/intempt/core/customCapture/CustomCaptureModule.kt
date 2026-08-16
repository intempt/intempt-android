@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core.customCapture

import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.ErrorReporter
import com.intempt.core.services.IntemptEventManagerService
import com.intempt.core.services.StorageManagerService
import com.intempt.core.services.UtilsService
import com.intempt.core.services.eventPool.EventPoolManagerService
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

@Module()
internal class CustomCaptureModule {
    @Provides
    @Singleton
    // Mirrors CustomCaptureComponent's constructor; Dagger provides each argument.
    @Suppress("LongParameterList")
    fun component(
        service: CustomCaptureService,
        config: ConfigManagerService,
        eventPool: EventPoolManagerService,
        intemptEvent: IntemptEventManagerService,
        utils: UtilsService,
        storage: StorageManagerService,
        errors: ErrorReporter,
    ): CustomCaptureComponent {
        return CustomCaptureComponent(
            service,
            config,
            eventPool,
            intemptEvent,
            utils,
            storage,
            errors,
        )
    }
}
