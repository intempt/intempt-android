package com.intempt.core.intemptCore

import com.intempt.core.customCapture.CustomCaptureComponent
import com.intempt.core.services.Logger
import com.intempt.core.services.eventPool.EventPoolManagerService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class IntemptCoreService  @Inject constructor(
    private val customCaptureComponent: CustomCaptureComponent,

) {


    internal val track: CustomCaptureComponent
        get() = customCaptureComponent








}