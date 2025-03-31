package com.intempt.core.services.firebase

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.HttpManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.firebase.model.PushNotificationMetadata
import com.intempt.core.services.firebase.webhook.WebhookService

class NotificationClickReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context != null) {
            val config = ConfigManagerService(context)
            val logger = LoggerManagerService(config);
            val http = HttpManagerService(config, logger)
            val webhookService = WebhookService(config, logger, http)
            val mapper = jacksonObjectMapper()
            val metadata = intent?.getParcelableExtra<PushNotificationMetadata>("metadata")
            Log.d("FCM", "Entered into NotificationClickReceiver")
            if (metadata != null) {
                val openedWebhookRequest = PushNotificationWebhookRequest(
                    PushNotificationWebhookRequest.WebhookType.OPENED,
                    metadata
                )
                webhookService.sendPushNotificationWebhook(mapper.valueToTree(openedWebhookRequest))
                Log.d("FCM", "Message tracked as opened")
            } else {
                Log.e("NotificationClickReceiver", "Metadata is null!")
            }
        }
    }
}