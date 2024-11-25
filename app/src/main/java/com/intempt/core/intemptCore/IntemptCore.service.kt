package com.intempt.core.intemptCore

import com.intempt.core.autocapture.AutoCaptureComponent
import com.intempt.core.customCapture.CustomCaptureComponent
import com.intempt.core.modifications.ModificationComponent
import com.intempt.core.services.StorageManagerService
import com.intempt.core.services.UtilsService
import com.intempt.core.types.IdTypeKeys
import com.intempt.core.types.StorageKeys
import com.intempt.core.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class IntemptCoreService  @Inject constructor(
    private val autoCaptureComponent: AutoCaptureComponent,
    private val customCaptureComponent: CustomCaptureComponent,
    private val modificationComponent: ModificationComponent,
) {
    internal val capture: CustomCaptureComponent
        get() = customCaptureComponent

    internal val modification : ModificationComponent
        get() = modificationComponent


    init{
        //TODO: need to configure init
        autoCaptureComponent.start()
        autoCaptureComponent.logger.log("Intempt SDK initialized")
        autoCaptureComponent.logger.log("VERSION: ${BuildConfig.sdkVersion}")
    }
}