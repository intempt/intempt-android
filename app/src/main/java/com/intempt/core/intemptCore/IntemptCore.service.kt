package com.intempt.core.intemptCore

import com.intempt.core.customCapture.CustomCaptureService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class IntemptCoreService  @Inject constructor(
    private val customCaptureComponent: CustomCaptureService
) {

    internal val track: CustomCaptureService
        get() = customCaptureComponent






}