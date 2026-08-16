package com.intempt.core.services.firebase

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.HttpManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.firebase.model.PushNotificationMetadata
import com.intempt.core.services.firebase.webhook.WebhookService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class NotificationDispatcherActivity : AppCompatActivity() {
    /**
     * Lazily built so an Activity launched by a notification tap does not pay for it until it logs.
     * These calls used to go straight to `android.util.Log`, so notification-tap diagnostics ignored
     * `Intempt.Logging.stop()`.
     */
    private val logger by lazy { LoggerManagerService(ConfigManagerService(applicationContext)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logger.debug("[FCM] NotificationDispatcherActivity started")

        val metadata = intent?.getParcelableExtra<PushNotificationMetadata>("metadata")
        val targetIntent = intent?.getParcelableExtra<Intent>("intent_test")

        if (metadata != null) {
            lifecycleScope.launch {
                sendWebhook(metadata)
            }
        } else {
            logger.error("[FCM_Dispatch] Metadata is null in dispatcher!")
        }

        if (targetIntent != null) {
            try {
                startActivity(targetIntent)
                logger.debug("[FCM] Target intent started from dispatcher")
            } catch (e: Exception) {
                logger.error("[FCM] Failed to start targetIntent from NotificationDispatcherActivity", e)
                val fallbackIntent = packageManager.getLaunchIntentForPackage(packageName)
                if (fallbackIntent != null) startActivity(fallbackIntent)
            }
        } else {
            logger.error("[FCM_Dispatch] TargetIntent is null in dispatcher!")
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

        val openedWebhookRequest =
            PushNotificationWebhookRequest(
                PushNotificationWebhookRequest.WebhookType.OPENED,
                metadata,
            )

        try {
            withContext(Dispatchers.IO) {
                webhookService.sendPushNotificationWebhook(mapper.valueToTree(openedWebhookRequest))
            }
            logger.debug("[FCM] Webhook sent from dispatcher")
        } catch (e: Exception) {
            logger.error("[FCM] Error sending webhook from NotificationDispatcherActivity", e)
        }
    }
}
