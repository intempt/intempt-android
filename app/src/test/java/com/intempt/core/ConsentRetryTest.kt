@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.intempt.core.eventModels.ConsentEvent
import com.intempt.core.eventModels.IntemptEvent
import com.intempt.core.queue.ConsentAuditLog
import com.intempt.core.queue.DeliveryMessages
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.HttpManagerService
import com.intempt.core.services.IntemptEventManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.PostOutcome
import com.intempt.core.services.eventPool.ConsentRetryPolicy
import com.intempt.core.services.eventPool.EventPoolManagerService
import com.intempt.core.services.eventPool.isConsentRetryable
import kotlinx.coroutines.test.TestScope
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
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * INT-3904: a consent decision whose POST fails is persisted and retried, like the Swift SDK.
 * The backend has no idempotency key, so a consent confirmed with 2xx must never be resent.
 */
@RunWith(RobolectricTestRunner::class)
class ConsentRetryTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val dbName = "intempt_consent_audit"

    private lateinit var config: ConfigManagerService
    private lateinit var http: HttpManagerService
    private var clock = 0L

    @Before
    fun setUp() {
        context.getDatabasePath(dbName).delete()
        config = mock(ConfigManagerService::class.java)
        whenever(config.isUserOptIn).thenReturn(true)
        whenever(config.consentUrl).thenReturn("https://example.invalid/consent")
        http = mock(HttpManagerService::class.java)
        clock = 0L
    }

    @After
    fun tearDown() {
        context.getDatabasePath(dbName).delete()
    }

    private fun TestScope.service(log: ConsentAuditLog = ConsentAuditLog(context)) =
        EventPoolManagerService(
            config,
            mock(LoggerManagerService::class.java),
            http,
            mock(IntemptEventManagerService::class.java),
            mock(DeliveryMessages::class.java),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            consentAudit = log,
        ).also { it.consentRetry = ConsentRetryPolicy(now = { clock }, jitterMs = { 0L }) }

    private fun consent(
        action: String,
        timestamp: Long = 1_000L,
    ) = IntemptEvent(
        name = "consent",
        type = "consent",
        payload =
            arrayOf(
                ConsentEvent(
                    eventId = "e-$action",
                    sessionId = "s1",
                    pageId = "p1",
                    profileId = "profile-1",
                    action = action,
                    sourceId = "src-1",
                    validUntil = 9_999_999L,
                    timestamp = timestamp,
                ),
            ),
    )

    private suspend fun stubPosts(vararg statuses: Int) {
        val outcomes = statuses.map { PostOutcome(it) }
        whenever(http.postForStatus(any(), any<JSONObject>(), any()))
            .thenReturn(outcomes.first(), *outcomes.drop(1).toTypedArray())
    }

    private suspend fun sentActions(times: Int = 1): List<String> {
        val captor = argumentCaptor<JSONObject>()
        verify(http, times(times)).postForStatus(any(), captor.capture(), any())
        return captor.allValues.map { it.getString("action") }
    }

    // (a)
    @Test
    fun `a failed post leaves the consent pending and a later drain re-sends it with its original timestamp`() =
        runTest {
            stubPosts(0, 200)
            val svc = service()

            svc.emitEvent(consent("opt_out", timestamp = 1_234L))
            testScheduler.advanceUntilIdle()
            svc.drainConsents()

            val captor = argumentCaptor<JSONObject>()
            verify(http, times(2)).postForStatus(any(), captor.capture(), any())
            assertEquals(listOf("opt_out", "opt_out"), captor.allValues.map { it.getString("action") })
            assertEquals(1_234L, captor.secondValue.getLong("timestamp"))
        }

    // (a) variant: 5xx, and survives a process restart (new log + service over the same file)
    @Test
    fun `a 503 leaves the consent pending across a restart`() =
        runTest {
            stubPosts(503)
            service().apply {
                emitEvent(consent("opt_out"))
                testScheduler.advanceUntilIdle()
            }
            stubPosts(200)

            service(ConsentAuditLog(context)).drainConsents()

            assertEquals(listOf("opt_out", "opt_out"), sentActions(times = 2))
        }

    // (b)
    @Test
    fun `a 2xx removes the consent from the pending queue and it is never re-sent`() =
        runTest {
            stubPosts(200)
            val svc = service()

            svc.emitEvent(consent("opt_in"))
            testScheduler.advanceUntilIdle()
            svc.drainConsents()
            svc.drainConsents()

            assertEquals(listOf("opt_in"), sentActions(times = 1))
        }

    // (c)
    @Test
    fun `pending consents drain oldest first`() =
        runTest {
            stubPosts(0, 0, 200, 200, 200)
            val svc = service()

            svc.emitEvent(consent("opt_in", 1L))
            svc.emitEvent(consent("opt_out", 2L))
            testScheduler.advanceUntilIdle()
            clock += 1_000_000L // past the backoff opened by the second failure
            svc.drainConsents()

            // two failed immediate attempts, then the drain sends opt_in before opt_out
            val sent = sentActions(times = 4)
            assertEquals(listOf("opt_in", "opt_in", "opt_out"), sent.takeLast(3))
        }

    // (d)
    @Test
    fun `400 413 422 are dropped only after three consecutive rejections`() =
        runTest {
            for (status in listOf(400, 413, 422)) {
                context.getDatabasePath(dbName).delete()
                http = mock(HttpManagerService::class.java)
                stubPosts(status)
                val svc = service()
                svc.emitEvent(consent("opt_in"))
                testScheduler.advanceUntilIdle()
                // the immediate attempt was strike 1
                clock += 1_000_000L
                svc.drainConsents()
                clock += 1_000_000L
                svc.drainConsents() // strike 3: dropped
                clock += 1_000_000L
                svc.drainConsents() // nothing left
                assertEquals("status $status", 3, sentActions(times = 3).size)
            }
        }

    @Test
    fun `every non-retryable status 300 to 599 is dropped after three rejections, never blocking the queue`() =
        runTest {
            for (status in (300..599).filter { !isConsentRetryable(it) }) {
                context.getDatabasePath(dbName).delete()
                http = mock(HttpManagerService::class.java)
                stubPosts(status)
                val svc = service()
                svc.emitEvent(consent("opt_in"))
                testScheduler.advanceUntilIdle()
                clock += 1_000_000L
                svc.drainConsents()
                clock += 1_000_000L
                svc.drainConsents() // strike 3: dropped
                clock += 1_000_000L
                svc.drainConsents() // nothing left
                assertEquals("status $status", 3, sentActions(times = 3).size)
            }
        }

    @Test
    fun `retryable statuses are exactly transport errors, 401, 402, 403, 404, 408, 429 and 5xx`() {
        for (status in listOf(0, 401, 402, 403, 404, 408, 429, 500, 503, 599)) {
            assertEquals("status $status", true, isConsentRetryable(status))
        }
        for (status in listOf(300, 302, 400, 409, 410, 413, 415, 422, 600)) {
            assertEquals("status $status", false, isConsentRetryable(status))
        }
    }

    @Test
    fun `a consent older than the maximum age is dropped even while the server answers 401`() =
        runTest {
            stubPosts(401)
            val svc = service()
            svc.emitEvent(consent("opt_in"))
            testScheduler.advanceUntilIdle() // the immediate attempt
            svc.consentClock = { System.currentTimeMillis() + 31L * 24 * 60 * 60 * 1000 }
            clock += 1_000_000L
            svc.drainConsents() // expired: dropped without a send
            clock += 1_000_000L
            svc.drainConsents() // nothing left
            assertEquals(1, sentActions(times = 1).size)
        }

    @Test
    fun `a consent recorded during backoff is sent when the window closes, without another trigger`() =
        runTest {
            stubPosts(0, 0, 200)
            val svc = service()
            svc.emitEvent(consent("c1"))
            svc.emitEvent(consent("c2")) // second failure on the head: backoff opens
            svc.emitEvent(consent("c3")) // recorded inside the window, not sent yet
            assertEquals(2, sentActions(times = 2).size)

            clock += 700_000L // past the maximum backoff
            testScheduler.advanceUntilIdle() // the scheduled drain fires

            // c1 again (the head), then c2 and c3, in order.
            assertEquals(listOf("c1", "c1", "c1", "c2", "c3"), sentActions(times = 5))
        }

    @Test
    fun `an oversized Retry-After is capped at the maximum backoff`() {
        var t = 1_000_000L
        val policy = ConsentRetryPolicy(now = { t })
        policy.recordFailure(Long.MAX_VALUE)
        t += ConsentRetryPolicy.MAX_BACKOFF_MS - 1
        assertEquals("still blocked just inside the cap", true, policy.requestNotAllowed)
        t += 1
        assertEquals("open again at the cap, not at Long.MAX_VALUE", false, policy.requestNotAllowed)
    }

    @Test
    fun `401 402 403 404 408 429 and 5xx are kept and retried until the age limit`() =
        runTest {
            for (status in listOf(401, 402, 403, 404, 500, 503, 408, 429)) {
                context.getDatabasePath(dbName).delete()
                http = mock(HttpManagerService::class.java)
                stubPosts(status)
                val svc = service()
                svc.emitEvent(consent("opt_in"))
                testScheduler.advanceUntilIdle()
                repeat(5) {
                    clock += 1_000_000L
                    svc.drainConsents()
                }
                assertEquals("status $status", 6, sentActions(times = 6).size)
            }
        }

    @Test
    fun `a dropped head no longer blocks the consent behind it`() =
        runTest {
            stubPosts(400, 400, 400, 200)
            val svc = service()
            svc.emitEvent(consent("opt_in", 1L)) // strike 1 (immediate)
            testScheduler.advanceUntilIdle()
            clock += 1_000_000L
            svc.drainConsents() // strike 2
            clock += 1_000_000L
            svc.drainConsents() // strike 3: dropped, then the next record drains
            svc.emitEvent(consent("opt_out", 2L))
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("opt_in", "opt_in", "opt_in", "opt_out"), sentActions(times = 4))
        }

    @Test
    fun `backoff gates the drain after two failures and Retry-After is honoured`() {
        val policy = ConsentRetryPolicy(now = { clock }, jitterMs = { 0L })
        policy.recordFailure()
        assertEquals(false, policy.requestNotAllowed)
        policy.recordFailure() // 2nd: 2^1*60s = 120s
        assertEquals(true, policy.requestNotAllowed)
        clock = 119_999L
        assertEquals(true, policy.requestNotAllowed)
        clock = 120_000L
        assertEquals(false, policy.requestNotAllowed)
        assertEquals(600_000L, policy.backoffMs(20))
        assertEquals(60_000L, policy.backoffMs(1))
    }

    // (e)
    @Test
    fun `upgrading a version 1 database keeps its rows and does not replay them`() =
        runTest {
            val path = context.getDatabasePath(dbName)
            path.parentFile?.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
                db.execSQL(
                    "CREATE TABLE consent_decisions (_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "payload TEXT NOT NULL, created_at INTEGER NOT NULL)",
                )
                db.execSQL("INSERT INTO consent_decisions (payload, created_at) VALUES ('{\"action\":\"opt_in\"}', 1)")
                db.version = 1
            }
            stubPosts(200)

            val log = ConsentAuditLog(context)
            service(log).drainConsents()

            assertEquals(1, log.getAll().size)
            assertEquals(0, mockingDetails(http).invocations.count { it.method.name == "postForStatus" })
        }

    private fun mockingDetails(m: Any) = org.mockito.Mockito.mockingDetails(m)
}
