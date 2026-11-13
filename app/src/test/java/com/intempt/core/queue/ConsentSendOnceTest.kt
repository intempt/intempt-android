package com.intempt.core.queue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** R3 (Beso 2026-11-13): consent outcomes, decided in pure code so the mutation gate reaches them. */
class ConsentSendOnceTest {
    @Test
    fun `a 2xx is delivered and every other outcome names its failure kind`() {
        for (status in 200..299) {
            assertNull("status $status", consentFailureKind(status, notSent = false))
            assertNull("status $status", consentFailureKind(status, notSent = true))
        }
        for (status in (100..199) + (300..999)) {
            assertEquals("status $status", DeliveryFailure.Kind.TERMINAL, consentFailureKind(status, notSent = false))
        }
        assertEquals(DeliveryFailure.Kind.TRANSPORT, consentFailureKind(0, notSent = true))
        assertEquals(DeliveryFailure.Kind.DROPPED, consentFailureKind(0, notSent = false))
        assertEquals(DeliveryFailure.Kind.DROPPED, consentFailureKind(-1, notSent = false))
    }

    @Test
    fun `the consent resend wait is the event transport's clamp, in milliseconds`() {
        assertEquals(-1L, consentResendDelayMs(500, 5_000L))
        assertEquals(-1L, consentResendDelayMs(0, null))
        assertEquals(1_000L, consentResendDelayMs(503, null))
        assertEquals(1_000L, consentResendDelayMs(503, 0L))
        assertEquals(1_000L, consentResendDelayMs(429, 1L))
        assertEquals(2_000L, consentResendDelayMs(429, 1_001L))
        assertEquals(7_000L, consentResendDelayMs(503, 7_000L))
        assertEquals(60_000L, consentResendDelayMs(503, 600_000L))
        assertEquals(60_000L, consentResendDelayMs(429, Long.MAX_VALUE))
    }

    @Test
    fun `a Retry-After is reported in whole seconds, rounded up, and absent when there was none`() {
        assertEquals(0, retryAfterSeconds(null))
        assertEquals(0, retryAfterSeconds(0L))
        assertEquals(0, retryAfterSeconds(-3L))
        assertEquals(1, retryAfterSeconds(1L))
        assertEquals(7, retryAfterSeconds(7_000L))
        assertEquals(8, retryAfterSeconds(7_001L))
        assertEquals(Int.MAX_VALUE, retryAfterSeconds(Long.MAX_VALUE))
    }
}
