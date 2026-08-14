package com.intempt.core.queue

import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the local audit trail that exists solely for one reason: EventPoolManagerService's
 * sendConsentEvent posts directly to the consent endpoint and previously dropped the decision
 * entirely on a failed send. If this class silently failed to persist, or lost ordering, a
 * compliance request for "this user's consent history" would be unanswerable from local state
 * again -- exactly the gap this class exists to close.
 */
@RunWith(RobolectricTestRunner::class)
class ConsentAuditLogTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        context.getDatabasePath("intempt_consent_audit").delete()
    }

    @After
    fun tearDown() {
        context.getDatabasePath("intempt_consent_audit").delete()
    }

    @Test
    fun recordThenGetAllReturnsTheSameDecision() {
        val log = ConsentAuditLog(context)
        val decision = JSONObject().put("action", "opt_in").put("profileId", "p1")

        log.record(decision)

        val all = log.getAll()
        assertEquals(1, all.size)
        assertEquals("opt_in", all[0].getString("action"))
        assertEquals("p1", all[0].getString("profileId"))
    }

    @Test
    fun multipleDecisionsArePreservedInOrder() {
        val log = ConsentAuditLog(context)
        log.record(JSONObject().put("action", "opt_in"))
        log.record(JSONObject().put("action", "opt_out"))
        log.record(JSONObject().put("action", "opt_in"))

        val all = log.getAll()
        assertEquals(3, all.size)
        assertEquals("opt_in", all[0].getString("action"))
        assertEquals("opt_out", all[1].getString("action"))
        assertEquals("opt_in", all[2].getString("action"))
    }

    @Test
    fun survivesAcrossInstancesBecauseItIsBackedByAFile() {
        // Simulates process death between recording a decision and any later read: a new
        // ConsentAuditLog instance, same backing database, must still see it.
        ConsentAuditLog(context).record(JSONObject().put("action", "opt_in"))

        val reopened = ConsentAuditLog(context).getAll()
        assertEquals(1, reopened.size)
    }

    @Test
    fun getAllOnAnEmptyLogReturnsEmptyRatherThanThrowing() {
        val all = ConsentAuditLog(context).getAll()
        assertTrue(all.isEmpty())
    }
}
