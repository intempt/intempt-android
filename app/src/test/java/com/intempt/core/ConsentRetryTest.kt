@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.intempt.core.eventModels.ConsentEvent
import com.intempt.core.eventModels.IntemptEvent
import com.intempt.core.queue.ConsentAuditLog
import com.intempt.core.queue.DeliveryMessages
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.ErrorReporter
import com.intempt.core.services.HttpManagerService
import com.intempt.core.services.IntemptEventManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.PostOutcome
import com.intempt.core.services.eventPool.EventPoolManagerService
import com.intempt.core.types.IntemptError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
 * INT-3904: a consent decision is persisted before it is sent, and a 2xx is never resent (the
 * backend has no idempotency key).
 *
 * R3 (Beso 2026-11-13): consent is send-once, like events and the other six SDKs. A 503 or 429 is
 * resent once after its Retry-After, clamped to 1-60 s. Any other answer drops the consent and is
 * reported terminal. A request that never reached the server is kept for the next drain and
 * reported once as transport; one that may have reached it (a failure after the write) is dropped
 * and reported as dropped. R2: every failure reaches the error listener as a DeliveryFailure.
 *
 * Rewritten for R3. The previous version pinned the consent-only taxonomy (403/404/408/429/5xx
 * kept and retried with a 60-600 s backoff, other statuses dropped after three rejections), which
 * is the behaviour the ruling removes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ConsentRetryTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val dbName = "intempt_consent_audit"

    private lateinit var config: ConfigManagerService
    private lateinit var http: HttpManagerService
    private val reported = mutableListOf<IntemptError.DeliveryFailure>()

    @Before
    fun setUp() {
        context.getDatabasePath(dbName).delete()
        config = mock(ConfigManagerService::class.java)
        whenever(config.isUserOptIn).thenReturn(true)
        whenever(config.consentUrl).thenReturn("https://example.invalid/consent")
        http = mock(HttpManagerService::class.java)
        reported.clear()
    }

    @After
    fun tearDown() {
        context.getDatabasePath(dbName).delete()
    }

    private fun reporter() =
        ErrorReporter(mock(LoggerManagerService::class.java)).apply {
            setListener { if (it is IntemptError.DeliveryFailure) reported += it }
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
        ).also { it.consents.errors = reporter() }

    private fun consent(
        action: String,
        timestamp: Long = 1_000L,
        userId: String? = null,
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
                    userId = userId,
                ),
            ),
    )

    private val notSent = PostOutcome(0, notSent = true)
    private val maybeReached = PostOutcome(0, notSent = false)

    private suspend fun stub(vararg outcomes: PostOutcome) {
        whenever(http.postForStatus(any(), any<JSONObject>(), any()))
            .thenReturn(outcomes.first(), *outcomes.drop(1).toTypedArray())
    }

    private suspend fun stubPosts(vararg statuses: Int) = stub(*statuses.map { PostOutcome(it) }.toTypedArray())

    private suspend fun sentActions(times: Int = 1): List<String> {
        val captor = argumentCaptor<JSONObject>()
        verify(http, times(times)).postForStatus(any(), captor.capture(), any())
        return captor.allValues.map { it.getString("action") }
    }

    @Test
    fun `a never-reached consent stays pending and a later drain sends it with its original timestamp`() =
        runTest {
            stub(notSent, PostOutcome(200))
            val svc = service()

            svc.emitEvent(consent("opt_out", timestamp = 1_234L))
            testScheduler.advanceUntilIdle()
            svc.drainConsents()

            val captor = argumentCaptor<JSONObject>()
            verify(http, times(2)).postForStatus(any(), captor.capture(), any())
            assertEquals(listOf("opt_out", "opt_out"), captor.allValues.map { it.getString("action") })
            assertEquals(1_234L, captor.secondValue.getLong("timestamp"))
        }

    @Test
    fun `a consent that never reached the server stays pending across a restart`() =
        runTest {
            stub(notSent)
            service().apply {
                emitEvent(consent("opt_out"))
                testScheduler.advanceUntilIdle()
            }
            stubPosts(200)

            service(ConsentAuditLog(context)).drainConsents()

            assertEquals(listOf("opt_out", "opt_out"), sentActions(times = 2))
        }

    // INT-7442: the pending record is the formatted body, so a resend after a restart must still
    // name the user the decision belongs to.
    @Test
    fun `a pending consent keeps its userId across a restart`() =
        runTest {
            stub(notSent)
            service().apply {
                emitEvent(consent("accept", userId = "u1"))
                testScheduler.advanceUntilIdle()
            }
            stubPosts(200)

            service(ConsentAuditLog(context)).drainConsents()

            val captor = argumentCaptor<JSONObject>()
            verify(http, times(2)).postForStatus(any(), captor.capture(), any())
            assertEquals(listOf("u1", "u1"), captor.allValues.map { it.getString("userId") })
        }

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
            assertTrue(reported.isEmpty())
        }

    @Test
    fun `pending consents drain oldest first`() =
        runTest {
            stub(notSent, notSent, PostOutcome(200))
            val svc = service()

            svc.emitEvent(consent("opt_in", 1L))
            svc.emitEvent(consent("opt_out", 2L))
            testScheduler.advanceUntilIdle()
            svc.drainConsents()

            // Each immediate attempt sends the head (opt_in) and stops there; the drain then sends
            // opt_in before opt_out.
            assertEquals(listOf("opt_in", "opt_in", "opt_in", "opt_out"), sentActions(times = 4))
        }

    @Test
    fun `every answered status other than 429 and 503 drops the consent on the first answer, reported terminal`() =
        runTest {
            for (status in (300..599).filter { it != 429 && it != 503 }) {
                context.getDatabasePath(dbName).delete()
                http = mock(HttpManagerService::class.java)
                reported.clear()
                stubPosts(status)
                val svc = service()
                svc.emitEvent(consent("opt_in"))
                testScheduler.advanceUntilIdle()
                svc.drainConsents() // nothing left

                assertEquals("status $status", 1, sentActions(times = 1).size)
                assertEquals("status $status", 1, reported.size)
                with(reported.single()) {
                    assertEquals("status $status", "terminal", kind)
                    assertEquals(status, this.status)
                    assertTrue(terminal)
                    assertEquals(1, eventCount)
                    assertEquals(emptyList<String>(), eventIds)
                }
            }
        }

    @Test
    fun `a 503 or 429 is resent once after its Retry-After and dropped if it fails again`() =
        runTest {
            for (status in listOf(503, 429)) {
                context.getDatabasePath(dbName).delete()
                http = mock(HttpManagerService::class.java)
                reported.clear()
                stub(PostOutcome(status, retryAfterMs = 7_000L))
                val svc = service()
                svc.emitEvent(consent("opt_in"))
                testScheduler.advanceTimeBy(6_999L)
                assertEquals("status $status: no resend before Retry-After", 1, sentActions(times = 1).size)
                testScheduler.advanceUntilIdle()
                svc.drainConsents() // dropped: nothing left

                assertEquals("status $status", 2, sentActions(times = 2).size)
                with(reported.single()) {
                    assertEquals("terminal", kind)
                    assertEquals(status, this.status)
                    assertEquals(7, retryAfter)
                }
            }
        }

    @Test
    fun `a 503 resent once and then accepted is delivered and not reported`() =
        runTest {
            stub(PostOutcome(503), PostOutcome(200))
            val svc = service()
            svc.emitEvent(consent("opt_in"))
            testScheduler.advanceUntilIdle()
            svc.drainConsents()

            assertEquals(2, sentActions(times = 2).size)
            assertTrue(reported.isEmpty())
        }

    @Test
    fun `the Retry-After wait is clamped to 1 to 60 seconds`() =
        runTest {
            for ((retryAfter, expectedWait) in listOf(null to 1_000L, 0L to 1_000L, 600_000L to 60_000L)) {
                context.getDatabasePath(dbName).delete()
                http = mock(HttpManagerService::class.java)
                stub(PostOutcome(503, retryAfterMs = retryAfter), PostOutcome(200))
                val svc = service()
                val start = testScheduler.currentTime
                svc.emitEvent(consent("opt_in"))
                testScheduler.advanceTimeBy(expectedWait - 1)
                assertEquals("Retry-After $retryAfter", 1, sentActions(times = 1).size)
                testScheduler.advanceTimeBy(2)
                assertEquals("Retry-After $retryAfter", 2, sentActions(times = 2).size)
                assertTrue(testScheduler.currentTime - start <= expectedWait + 1)
            }
        }

    @Test
    fun `a consent that never reached the server is reported once as transport, not on every drain`() =
        runTest {
            stub(notSent)
            val svc = service()
            svc.emitEvent(consent("opt_in"))
            testScheduler.advanceUntilIdle()
            svc.drainConsents()
            svc.drainConsents()

            assertEquals(3, sentActions(times = 3).size)
            with(reported.single()) {
                assertEquals("transport", kind)
                assertFalse(terminal)
                assertNull(status)
                assertEquals(emptyList<String>(), eventIds)
            }
        }

    @Test
    fun `a failure after the write drops the consent, reported as dropped`() =
        runTest {
            stub(maybeReached)
            val svc = service()
            svc.emitEvent(consent("opt_in"))
            testScheduler.advanceUntilIdle()
            svc.drainConsents()

            assertEquals(1, sentActions(times = 1).size)
            with(reported.single()) {
                assertEquals("dropped", kind)
                assertTrue(terminal)
            }
        }

    @Test
    fun `a dropped head does not block the consent behind it`() =
        runTest {
            stubPosts(400, 200)
            val svc = service()
            svc.emitEvent(consent("opt_in", 1L))
            testScheduler.advanceUntilIdle()
            svc.emitEvent(consent("opt_out", 2L))
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("opt_in", "opt_out"), sentActions(times = 2))
        }

    @Test
    fun `a consent older than the maximum age is dropped and reported`() =
        runTest {
            stub(notSent)
            val svc = service()
            svc.emitEvent(consent("opt_in"))
            testScheduler.advanceUntilIdle() // the immediate attempt
            svc.consentClock = { System.currentTimeMillis() + 31L * 24 * 60 * 60 * 1000 }
            svc.drainConsents() // expired: dropped without a send
            svc.drainConsents() // nothing left

            assertEquals(1, sentActions(times = 1).size)
            assertEquals(listOf("transport", "dropped"), reported.map { it.kind })
        }

    @Test
    fun `without a local store a consent is still sent once and its failure reported`() =
        runTest {
            stubPosts(400)
            val svc =
                EventPoolManagerService(
                    config,
                    mock(LoggerManagerService::class.java),
                    http,
                    mock(IntemptEventManagerService::class.java),
                    mock(DeliveryMessages::class.java),
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                    consentAudit = null,
                ).also { it.consents.errors = reporter() }
            svc.emitEvent(consent("opt_in"))
            testScheduler.advanceUntilIdle()

            assertEquals(1, sentActions(times = 1).size)
            assertEquals("terminal", reported.single().kind)
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
