package com.intempt.core.services.firebase

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.HttpManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.firebase.model.PushNotificationMetadata
import com.intempt.core.services.firebase.webhook.WebhookService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class NotificationDispatcherActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("FCM", "NotificationDispatcherActivity started")

        val metadata = intent?.getParcelableExtra<PushNotificationMetadata>("metadata")
        val targetIntent = intent?.getParcelableExtra<Intent>("intent_test")

        if (metadata != null) {

            lifecycleScope.launch {
                sendWebhook(metadata)
            }
        } else {
            Log.e("FCM_Dispatch", "Metadata is null in dispatcher!")
        }

        if (targetIntent != null) {
            try {
                startActivity(targetIntent)
                Log.d("FCM", "Target intent started from dispatcher")
            } catch (e: Exception) {
                Log.e("FCM", "Ошибка запуска targetIntent из NotificationDispatcherActivity", e)
                 val fallbackIntent = packageManager.getLaunchIntentForPackage(packageName)
                 if (fallbackIntent != null) startActivity(fallbackIntent)
            }
        } else {
            Log.e("FCM_Dispatch", "TargetIntent is null in dispatcher!")
             val fallbackIntent = packageManager.getLaunchIntentForPackage(packageName)
             if (fallbackIntent != null) startActivity(fallbackIntent)
        }

        finish()
    }

    private suspend fun sendWebhook(metadata: PushNotificationMetadata) {
        val config = ConfigManagerService(applicationContext)
        val logger = LoggerManagerService(config)
        val http = HttpManagerService(config, logger)
        val webhookService = WebhookService(config, logger, http)
        val mapper = jacksonObjectMapper()

        val openedWebhookRequest = PushNotificationWebhookRequest(
            PushNotificationWebhookRequest.WebhookType.OPENED,
            metadata
        )

        try {
            withContext(Dispatchers.IO) {
                webhookService.sendPushNotificationWebhook(mapper.valueToTree(openedWebhookRequest))
            }
            Log.d("FCM", "Webhook sent from dispatcher")
        } catch (e: Exception) {
            Log.e("FCM", "Error webhook sending from NotificationDispatcherActivity", e)
        }
    }
}