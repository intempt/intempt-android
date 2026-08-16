package com.intempt.core.queue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Guards the backoff and bulk-flush decisions extracted from {@code DeliveryMessages}'s
 * delivery-thread handler, which cannot be unit tested directly because it depends on
 * {@code android.os.Handler}/{@code Looper}.
 *
 * <p>Both decisions govern how aggressively the queue talks to the network under failure: too
 * short a backoff hammers a struggling server, too long wedges legitimate retries for minutes
 * after they could have succeeded, and a wrong bulk-flush decision either defeats batching
 * entirely or lets the on-disk queue grow unbounded between periodic timer ticks.
 */
public class DeliveryRetryPolicyTest {

    @Test
    public void firstFailureWaitsOneMinute() {
        assertEquals(60_000L, DeliveryRetryPolicy.nextRetryDelay(0, 0));
    }

    @Test
    public void backoffDoublesWithEachFailure() {
        assertEquals(60_000L, DeliveryRetryPolicy.nextRetryDelay(0, 0));
        assertEquals(120_000L, DeliveryRetryPolicy.nextRetryDelay(1, 0));
        assertEquals(240_000L, DeliveryRetryPolicy.nextRetryDelay(2, 0));
        assertEquals(480_000L, DeliveryRetryPolicy.nextRetryDelay(3, 0));
    }

    @Test
    public void backoffIsCappedAtTenMinutes() {
        // Ten doublings of the one-minute base would be over 17 hours; the cap must bite.
        assertEquals(600_000L, DeliveryRetryPolicy.nextRetryDelay(10, 0));
    }

    @Test
    public void aServerSuppliedFloorIsNeverShortened() {
        // A 503's Retry-After can exceed the exponential value on an early failure. The
        // caller-supplied floor must win so Intempt does not retry sooner than the server
        // explicitly asked for.
        assertEquals(500_000L, DeliveryRetryPolicy.nextRetryDelay(0, 500_000L));
    }

    @Test
    public void theFloorIsAlsoSubjectToTheTenMinuteCap() {
        // Regression: upstream once left the floor unclamped, so one very large Retry-After
        // could wedge every later retry beyond the documented ceiling.
        assertEquals(600_000L, DeliveryRetryPolicy.nextRetryDelay(0, 999_000_000L));
    }

    @Test
    public void aQueueAtTheBulkLimitFlushesImmediately() {
        assertTrue(DeliveryRetryPolicy.shouldFlushOnBulkLimit(50, 50, -2, 0, "token"));
    }

    @Test
    public void aQueueBelowTheBulkLimitDoesNotFlushYet() {
        assertFalse(DeliveryRetryPolicy.shouldFlushOnBulkLimit(49, 50, -2, 0, "token"));
    }

    @Test
    public void theOutOfMemorySentinelAlwaysFlushesRegardlessOfLimit() {
        // The DB adapter could not even count the rows, so it might be far past the limit
        // already; treat it as urgent rather than risk letting it grow further.
        assertTrue(DeliveryRetryPolicy.shouldFlushOnBulkLimit(-2, 50, -2, 0, "token"));
    }

    @Test
    public void aPendingRetryBackoffSuppressesTheBulkFlush() {
        // Flushing while a retry is already scheduled would just re-fail immediately without
        // resetting anything; the scheduled retry is left to run instead.
        assertFalse(DeliveryRetryPolicy.shouldFlushOnBulkLimit(999, 50, -2, 1, "token"));
    }

    @Test
    public void aNullTokenNeverTriggersAFlush() {
        assertFalse(DeliveryRetryPolicy.shouldFlushOnBulkLimit(999, 50, -2, 0, null));
    }

    /**
     * The class is a namespace for two static decisions and holds no state, so an instance of it
     * would be meaningless. Calling the constructor reflectively also keeps PIT's line-coverage
     * threshold from being dragged down by a deliberately-unreachable line.
     */
    @Test
    public void thePolicyCannotBeInstantiated() throws Exception {
        final java.lang.reflect.Constructor<DeliveryRetryPolicy> constructor =
                DeliveryRetryPolicy.class.getDeclaredConstructor();

        assertTrue(
                "a stateless policy must not be instantiable",
                java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()));

        constructor.setAccessible(true);
        constructor.newInstance();
    }
}
