package com.intempt.core.services.pushNotifications

import android.content.Context
import com.intempt.core.services.LoggerManagerService
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

@Module
internal class PushNotificationModule {
    
    @Provides
    @Singleton
    fun providePushNotificationEngine(
        context: Context,
        logger: LoggerManagerService
    ): PushNotificationEngine {
        return PushNotificationEngine(context, logger)
    }
    
    @Provides
    @Singleton
    fun provideFCMConsumerService(
        pushNotificationEngine: PushNotificationEngine,
        logger: LoggerManagerService
    ): FCMConsumerService {
        return FCMConsumerService(pushNotificationEngine, logger)
    }
}
