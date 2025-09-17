package com.intempt.core.services.pushNotifications

import android.content.Context
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.intempt.core.services.LoggerManagerService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class FCMConsumerService @Inject constructor(
    private val pushNotificationEngine: PushNotificationEngine,
    private val logger: LoggerManagerService
) : FirebaseMessagingService() {

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        
        logger.log("FCMConsumerService | Received FCM message: ${remoteMessage.messageId}")
        
        try {
            val title = remoteMessage.notification?.title ?: remoteMessage.data["title"] ?: ""
            val body = remoteMessage.notification?.body ?: remoteMessage.data["body"] ?: ""
            val url = remoteMessage.data["url"]
            val imageUrl = remoteMessage.data["image_url"]
            
            val pushData = PushNotificationData(
                title = title,
                body = body,
                url = url,
                imageUrl = imageUrl
            )
            
            pushNotificationEngine.processPushNotification(pushData)
            
        } catch (e: Exception) {
            logger.error("FCMConsumerService | Error processing FCM message: ${e.message}")
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        logger.log("FCMConsumerService | New FCM token received: $token")
        
        // Store token for future use
        val sharedPrefs = getSharedPreferences("intempt_fcm", Context.MODE_PRIVATE)
        sharedPrefs.edit().putString("fcm_token", token).apply()
    }
}
