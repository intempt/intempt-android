@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core

import androidx.test.core.app.ApplicationProvider
import com.intempt.core.eventModels.ConsentEvent
import com.intempt.core.eventModels.IntemptEvent
import com.intempt.core.queue.ConsentAuditLog
import com.intempt.core.queue.DeliveryMessages
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.HttpManagerService
import com.intempt.core.services.IntemptEventManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.eventPool.EventPoolManagerService
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * Consent events post directly to the consent endpoint instead of going through the durable
 * queue (EventPoolManagerService.sendConsentEvent). Before this fix, a failed -- or never
 * completed -- send left no local trace that the decision was ever made: the catch block only
 * logged and moved on. These tests pin the fallback: the decision must land in ConsentAuditLog
 * regardless of whether the network call to the consent endpoint succeeds or fails.
 */
@RunWith(RobolectricTestRunner::class)
class EventPoolManagerServiceConsentAuditTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private lateinit var auditLog: ConsentAuditLog
    private lateinit var config: ConfigManagerService
    private lateinit var logger: LoggerManagerService
    private lateinit var http: HttpManagerService
    private lateinit var intemptEvent: IntemptEventManagerService

    @Before
    fun setUp() {
        context.getDatabasePath("intempt_consent_audit").delete()
        auditLog = ConsentAuditLog(context)

        config = mock(ConfigManagerService::class.java)
        whenever(config.isUserOptIn).thenReturn(true)
        whenever(config.consentUrl).thenReturn("https://example.invalid/consent")

        logger = mock(LoggerManagerService::class.java)
        http = mock(HttpManagerService::class.java)
        intemptEvent = mock(IntemptEventManagerService::class.java)
    }

    @After
    fun tearDown() {
        context.getDatabasePath("intempt_consent_audit").delete()
    }

    private fun consentEvent(action: String): IntemptEvent {
        val consent =
            ConsentEvent(
                eventId = "e1",
                sessionId = "s1",
                pageId = "p1",
                profileId = "profile-1",
                action = action,
                sourceId = "src-1",
                validUntil = System.currentTimeMillis() + 1000,
            )
        return IntemptEvent(name = "consent", type = "consent", payload = arrayOf(consent))
    }

    @Test
    fun `consent decision is recorded locally even when the network send fails`() =
        runTest {
            whenever(http.post(any(), any<JSONObject>(), any())).thenThrow(RuntimeException("network down"))

            val service =
                EventPoolManagerService(
                    config,
                    logger,
                    http,
                    intemptEvent,
                    mock(DeliveryMessages::class.java),
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                    consentAudit = auditLog,
                )

            service.emitEvent(consentEvent("opt_out"))
            testScheduler.advanceUntilIdle()

            val recorded = auditLog.getAll()
            assertEquals(1, recorded.size)
            assertEquals("opt_out", recorded[0].getString("action"))
        }

    /**
     * A rejected consent event must not be logged as a delivered one.
     *
     * `HttpManagerService.post` catches its own failures -- including every non-2xx -- and reports
     * them by returning null rather than throwing, so the call above it completed normally on a 401
     * and the next line logged success unconditionally. Observed on a real device:
     *
     *     HttpService post request error: Failed with status code: 401
     *     Successfully sent events to server
     *
     * The throwing case was already covered by the test above; only the null case reached this,
     * and nothing asserted on the log. Consent bypasses the durable queue, so there is no retry
     * and no later signal -- this line is the only place a failed compliance decision surfaces.
     */
    @Test
    fun `a rejected consent send is logged as a failure, not as a success`() =
        runTest {
            // null, not an exception: this is what a 401, 404 or 500 produces.
            whenever(http.post(any(), any<JSONObject>(), any())).thenReturn(null)

            val service =
                EventPoolManagerService(
                    config,
                    logger,
                    http,
                    intemptEvent,
                    mock(DeliveryMessages::class.java),
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                    consentAudit = auditLog,
                )

            service.emitEvent(consentEvent("opt_out"))
            testScheduler.advanceUntilIdle()

            verify(logger).error(argThat { contains("NOT delivered") })
            verify(logger, never()).log(argThat { contains("Successfully sent consent") })

            // The local audit record is still the point of the fallback, and it must survive a
            // failure that is now reported honestly.
            assertEquals(1, auditLog.getAll().size)
        }

    @Test
    fun `consent decision is recorded locally even when the network send succeeds`() =
        runTest {
            val response = mock(HttpResponse::class.java)
            whenever(http.post(any(), any<JSONObject>(), any())).thenReturn(response)

            val service =
                EventPoolManagerService(
                    config,
                    logger,
                    http,
                    intemptEvent,
                    mock(DeliveryMessages::class.java),
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                    consentAudit = auditLog,
                )

            service.emitEvent(consentEvent("opt_in"))
            testScheduler.advanceUntilIdle()

            val recorded = auditLog.getAll()
            assertEquals(1, recorded.size)
            assertEquals("opt_in", recorded[0].getString("action"))
        }

    @Test
    fun `a null audit log does not break consent sending`() =
        runTest {
            val response = mock(HttpResponse::class.java)
            whenever(http.post(any(), any<JSONObject>(), any())).thenReturn(response)

            val service =
                EventPoolManagerService(
                    config,
                    logger,
                    http,
                    intemptEvent,
                    mock(DeliveryMessages::class.java),
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )

            service.emitEvent(consentEvent("opt_in"))
            testScheduler.advanceUntilIdle()
        }
}
