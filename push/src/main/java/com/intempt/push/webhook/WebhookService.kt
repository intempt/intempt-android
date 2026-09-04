@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.push.webhook

import com.fasterxml.jackson.databind.JsonNode
import com.intempt.core.autocapture.BaseComponent
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.HttpManagerService
import com.intempt.core.services.LoggerManagerService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Singleton

@Singleton
internal class WebhookService(
    private val config: ConfigManagerService,
    private val logger: LoggerManagerService,
    private val http: HttpManagerService,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BaseComponent(logger) {
    private val coroutineScope = CoroutineScope(SupervisorJob() + dispatcher)
    private var lastDispatchTime: Long = System.currentTimeMillis()

    /**
     * Reports a push lifecycle event (delivered, bounced, opened).
     *
     * These were previously fire-and-forget: a single failed POST was logged and the
     * signal lost. That matters more than a lost analytics event, because journeys branch
     * on these — a dropped DELIVERED makes a journey believe the push never arrived and
     * send the wrong follow-up to a real person.
     *
     * The retry is bounded and in-process rather than routed through the durable event
     * queue, because the queue posts to the ingestion endpoint and these go to the webhook
     * endpoint with a different body shape. Bounded retry closes the common case — a
     * transient blip — without inventing a second persistence layer.
     */
    fun sendPushNotificationWebhook(requestBodyJson: JsonNode) {
        logger.debug("[FCM] sendPushNotificationWebhook triggered")
        coroutineScope.launch {
            var delayMs = INITIAL_RETRY_DELAY_MS
            repeat(MAX_ATTEMPTS) { attempt ->
                // THE gate, re-asked before EVERY attempt.
                //
                // One check here rather than one at each of the three call sites — delivered and
                // bounced in FirebaseService, opened in NotificationDispatcherActivity — because a
                // gate per call site is a gate a fourth call site silently does not get. Inside
                // the loop rather than before it because a retry is a NEW request and the backoff
                // spans seven seconds, long enough for someone to opt out inside it.
                //
                // This module never consulted opt-out at all: `isUserOptIn` appeared zero times
                // under `push/`. The body carries `masterId` and `accountId`, which identify a
                // person, so a user who had objected still had their identifiers posted on every
                // delivery, bounce and open — four times each, once the retry landed.
                //
                // Only the REPORT stops. The notification is still rendered: the platform has
                // already delivered it, and hiding it would be a second, visible change the user
                // never asked for.
                if (!config.isUserOptIn) {
                    logger.debug("[FCM] push webhook not sent: the user has opted out")
                    return@launch
                }
                val sent =
                    try {
                        http.post(config.pushNotificationWebhookUrl, JSONObject(requestBodyJson.toString())) != null
                    } catch (e: Throwable) {
                        logger.error("sendPushNotificationWebhook | attempt ${attempt + 1} failed: ${e.message}")
                        false
                    }
                if (sent) {
                    logger.log("Successfully sent event webhook to server")
                    lastDispatchTime = System.currentTimeMillis()
                    return@launch
                }
                if (attempt < MAX_ATTEMPTS - 1) {
                    delay(delayMs)
                    delayMs *= 2
                }
            }
            logger.error(
                "sendPushNotificationWebhook | giving up after $MAX_ATTEMPTS attempts; " +
                    "a journey branching on this signal will not see it",
            )
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 4
        const val INITIAL_RETRY_DELAY_MS = 1_000L
    }
}
