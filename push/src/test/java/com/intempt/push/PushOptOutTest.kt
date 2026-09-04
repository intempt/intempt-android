package com.intempt.push

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.HttpManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.push.model.PushNotificationMetadata
import com.intempt.push.webhook.WebhookService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Test
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

/**
 * The push webhook body carries `masterId` and `accountId`, which identify a person.
 *
 * Before this, `push/` never consulted opt-out at all -- `isUserOptIn` appeared zero times in the
 * module -- so a user who had objected still had those identifiers posted on every delivery,
 * bounce and open, four times each once the retry landed. `optOut()`'s own contract is "stops
 * event capture and discards what is already queued"; this was the one path it did not reach.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PushOptOutTest {
    private val mapper = jacksonObjectMapper()

    private fun body(): JsonNode =
        mapper.valueToTree(
            PushNotificationWebhookRequest(
                PushNotificationWebhookRequest.WebhookType.DELIVERED,
                PushNotificationMetadata(
                    orgId = "11",
                    projectId = "22",
                    transformerId = "33",
                    pipelineId = "44",
                    destinationId = "55",
                    masterId = "66",
                    accountId = "77",
                    templateId = "88",
                ),
            ),
        )

    @Test
    fun `an opted-out user has no push report sent`() =
        runTest {
            val config = mock(ConfigManagerService::class.java)
            whenever(config.isUserOptIn).thenReturn(false)
            whenever(config.pushNotificationWebhookUrl).thenReturn("https://example.invalid/webhook")
            val http = mock(HttpManagerService::class.java)

            WebhookService(
                config,
                mock(LoggerManagerService::class.java),
                http,
                UnconfinedTestDispatcher(testScheduler),
            ).sendPushNotificationWebhook(body())

            verify(http, never()).post(any(), any<JSONObject>(), any())
        }

    /**
     * The ungated path must still send, or the test above passes for the wrong reason: a gate
     * that blocks everything is indistinguishable from one that works.
     */
    @Test
    fun `an opted-in user still has the report sent`() =
        runTest {
            val config = mock(ConfigManagerService::class.java)
            whenever(config.isUserOptIn).thenReturn(true)
            whenever(config.pushNotificationWebhookUrl).thenReturn("https://example.invalid/webhook")
            val http = mock(HttpManagerService::class.java)
            whenever(http.post(any(), any<JSONObject>(), any())).thenReturn(null)

            WebhookService(
                config,
                mock(LoggerManagerService::class.java),
                http,
                UnconfinedTestDispatcher(testScheduler),
            ).sendPushNotificationWebhook(body())

            verify(http, atLeastOnce()).post(any(), any<JSONObject>(), any())
        }

    /**
     * A retry is a NEW request, and the backoff spans seven seconds -- long enough for someone to
     * opt out inside it. A gate checked only before the loop would let three more
     * identifier-carrying POSTs leave after the objection.
     */
    @Test
    fun `opting out mid-backoff stops the remaining retries`() =
        runTest {
            val config = mock(ConfigManagerService::class.java)
            whenever(config.pushNotificationWebhookUrl).thenReturn("https://example.invalid/webhook")
            whenever(config.isUserOptIn).thenReturn(true, false)
            val http = mock(HttpManagerService::class.java)
            whenever(http.post(any(), any<JSONObject>(), any())).thenReturn(null)

            WebhookService(
                config,
                mock(LoggerManagerService::class.java),
                http,
                UnconfinedTestDispatcher(testScheduler),
            ).sendPushNotificationWebhook(body())

            testScheduler.advanceUntilIdle()

            verify(http, times(1)).post(any(), any<JSONObject>(), any())
        }
}
