package com.intempt.core.intemptCore

import com.intempt.core.customCapture.CustomCaptureComponent
import com.intempt.core.services.LoggerManagerService
import com.intempt.sdk.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class IntemptCoreService  @Inject constructor(
    private val logger: LoggerManagerService,
    private val customCaptureComponent: CustomCaptureComponent,
) {
    init{
        logger.log("Intempt SDK initialized")
        logger.log("VERSION: ${BuildConfig.sdkVersion}")
    }


    internal val capture: CustomCaptureComponent
        get() = customCaptureComponent








}