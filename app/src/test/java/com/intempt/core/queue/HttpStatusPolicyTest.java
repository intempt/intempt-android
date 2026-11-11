package com.intempt.core.queue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Ruling R-retry as amended by CONTRACT-R2 S1 (INT-7436): only 503 and 429 are resent, once, by
 * the transport; every status still standing after that drops; consent keeps its own taxonomy.
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

    /** Consent's taxonomy is the pre-INT-7436 one, frozen (plan2/android.md decision (c)). */
    @Test
    public void consentKeepsItsRetryTaxonomy() {
        for (int status = -1; status <= 999; status++) {
            final boolean expected =
                    status <= 0
                            || status == 403
                            || status == 404
                            || status == 408
                            || status == 429
                            || (status >= 500 && status <= 599);
            assertEquals("status " + status, expected, HttpStatusPolicy.isConsentRetryable(status));
        }
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
