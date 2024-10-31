package com.intempt.core

import android.content.Context
import com.intempt.core.intemptCore.DaggerIntemptCoreComponent
import com.intempt.core.intemptCore.IntemptCoreComponent
import com.intempt.core.intemptCore.IntemptCoreModule
import com.intempt.core.intemptCore.IntemptCoreService
import com.intempt.core.services.Logger
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

    fun identify(userId: String, eventTitle: String?, userAttributes: Map<String, String>?, data: Map<String, String>?) {
        intemptCoreService.track.identify(userId, eventTitle, userAttributes, data)
    }

    fun group(accountId: String, eventTitle: String?, accountAttributes: Map<String, String>?) {
        intemptCoreService.track.group(accountId, eventTitle, accountAttributes)
    }

    fun track( eventTitle: String, data: Map<String, String>) {
        intemptCoreService.track.track( eventTitle, data)
    }

    fun record(eventTitle: String, accountId: String?, userId: String?, accountAttributes: Map<String, String>?, userAttributes: Map<String, String>?, data: Map<String, String>?) {
        intemptCoreService.track.record(
            eventTitle,
            accountId,
            userId,
            accountAttributes,
            userAttributes,
            data
        )
    }

}