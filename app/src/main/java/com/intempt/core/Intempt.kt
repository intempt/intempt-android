package com.intempt.core

import android.content.Context
import com.intempt.core.intemptCore.DaggerIntemptCoreComponent
import com.intempt.core.intemptCore.IntemptCoreComponent
import com.intempt.core.intemptCore.IntemptCoreModule
import com.intempt.core.intemptCore.IntemptCoreService
import com.intempt.core.services.Logger
import com.intempt.core.types.AutoCaptureParam
import com.intempt.sdk.BuildConfig


object Intempt  {
    private lateinit var component: IntemptCoreComponent
    private lateinit var intemptCoreService: IntemptCoreService

    fun initialize(context: Context) {
        component = DaggerIntemptCoreComponent.factory()
            .create(IntemptCoreModule(context));

        component.inject(this);

        intemptCoreService = component.initService()


        Logger.log("Intempt SDK initialized")
        Logger.log("VERSION: ${BuildConfig.sdkVersion}")
    }

    fun autoCapture(listenerType: String, param: AutoCaptureParam) {
       intemptCoreService.autoCapture(listenerType, param)
    }


}