@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core.intemptCore

import com.intempt.core.BuildConfig
import com.intempt.core.autocapture.AutoCaptureComponent
import com.intempt.core.customCapture.CustomCaptureComponent
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class IntemptCoreService
    @Inject
    constructor(
        private val autoCaptureComponent: AutoCaptureComponent,
        private val customCaptureComponent: CustomCaptureComponent,
    ) {
        internal val capture: CustomCaptureComponent
            get() = customCaptureComponent

        init {
            // TODO: need to configure init
            autoCaptureComponent.start()
            autoCaptureComponent.logger.log("Intempt SDK initialized")
            autoCaptureComponent.logger.log("VERSION: ${BuildConfig.sdkVersion}")
        }
    }
