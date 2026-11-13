package com.intempt.core.queue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Ruling R-retry as amended by CONTRACT-R2 S1 (INT-7436): only 503 and 429 are resent, once, by
 * the transport; every status still standing after that drops. Consent follows the same rule
 * (R3, Beso 2026-11-13).
 *
 * <p>Rewritten for INT-7436. The previous version pinned 403/404/408/429/5xx as retried-and-kept
 * (INT-5897), which is exactly the behaviour the ruling removes.
 */
public class HttpStatusPolicyTest {

    @Test
    public void only503And429AreResentOnce() {
        for (int status = 100; status <= 999; status++) {
            assertEquals("status " + status, status == 503 || status == 429, HttpStatusPolicy.isResentOnce(status));
        }
    }

    /** 300 exactly drops: a redirect is not followed, so it can never succeed as sent. */
    @Test
    public void everyAnsweredFailureDrops() {
        for (int status = 300; status <= 999; status++) {
            assertTrue("status " + status + " must drop", HttpStatusPolicy.shouldDrop(status));
        }
    }

    @Test
    public void successAndNoStatusDoNotDrop() {
        assertFalse(HttpStatusPolicy.shouldDrop(200));
        assertFalse(HttpStatusPolicy.shouldDrop(299));
        assertFalse(HttpStatusPolicy.shouldDrop(0));
        assertFalse(HttpStatusPolicy.shouldDrop(-1));
    }

    /** CONTRACT-R2 S1.2: Retry-After clamped to [1, 60] s, 1 s when absent or not positive. */
    @Test
    public void resendDelayIsClampedForTheTwoResentStatusesAndAbsentForEveryOther() {
        for (int status = -1; status <= 999; status++) {
            final boolean resent = status == 503 || status == 429;
            assertEquals("status " + status, resent ? 7 : -1, HttpStatusPolicy.resendDelaySeconds(status, 7));
        }
        assertEquals(1, HttpStatusPolicy.resendDelaySeconds(503, 0));
        assertEquals(1, HttpStatusPolicy.resendDelaySeconds(503, -5));
        assertEquals(1, HttpStatusPolicy.resendDelaySeconds(429, 1));
        assertEquals(2, HttpStatusPolicy.resendDelaySeconds(429, 2));
        assertEquals(59, HttpStatusPolicy.resendDelaySeconds(503, 59));
        assertEquals(60, HttpStatusPolicy.resendDelaySeconds(503, 60));
        assertEquals(60, HttpStatusPolicy.resendDelaySeconds(429, 61));
        assertEquals(60, HttpStatusPolicy.resendDelaySeconds(503, Integer.MAX_VALUE));
    }

    /** Executes the private constructor so PIT's line coverage measures the decisions. */
    @Test
    public void thePolicyCannotBeInstantiated() throws Exception {
        final java.lang.reflect.Constructor<HttpStatusPolicy> constructor =
                HttpStatusPolicy.class.getDeclaredConstructor();

        assertTrue(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()));
        constructor.setAccessible(true);
        constructor.newInstance();
    }
}
