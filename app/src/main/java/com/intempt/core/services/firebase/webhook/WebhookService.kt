package com.intempt.core.services.firebase.webhook

import com.fasterxml.jackson.databind.JsonNode
import com.intempt.core.autocapture.BaseComponent
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.HttpManagerService
import com.intempt.core.services.LoggerManagerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Singleton

@Singleton
class WebhookService (
    private val config: ConfigManagerService,
    private val logger: LoggerManagerService,
    private val http: HttpManagerService
): BaseComponent(logger){
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastDispatchTime: Long = System.currentTimeMillis();

    fun sendPushNotificationWebhook(requestBodyJson: JsonNode) {
        coroutineScope.launch {
            try {
                http.post(config.pushNotificationWebhookUrl, JSONObject(requestBodyJson.toString()))
                logger.log("Successfully sent event webhook to server")
                lastDispatchTime = System.currentTimeMillis()
            }
            catch (e: Exception) {
                logger.error("sendPushNotificationWebhook | Exception occurred while sending event webhook: ${e.message}")
            }
        }
    }
}