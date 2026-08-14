package com.intempt.core.queue;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Guards the one place this queue deliberately departs from inherited behaviour.
 *
 * <p>Inherited: {@code ClientErrorException extends IOException}, and
 * {@code DeliveryMessages.sendData} catches {@code IOException} with
 * {@code deleteEvents = false}, so every 4xx is requeued with backoff forever. For 429
 * that is correct. For 401, 403, 400 and 422 it means one permanently-failing batch sits
 * at the head of the queue and blocks all delivery for the process lifetime.
 */
public class HttpStatusPolicyTest {

    @Test
    public void rateLimitIsRetriedNotDropped() {
        // Intempt's gateway signals backpressure with 429 plus X-RateLimit-* headers and
        // no Retry-After. Dropping here would discard events during normal throttling.
        assertTrue(HttpStatusPolicy.isRetryable(429));
        assertFalse(HttpStatusPolicy.shouldDrop(429));
    }

    @Test
    public void serverErrorsAreRetried() {
        assertTrue(HttpStatusPolicy.isRetryable(500));
        assertTrue(HttpStatusPolicy.isRetryable(502));
        assertTrue(HttpStatusPolicy.isRetryable(503));
        assertFalse(HttpStatusPolicy.shouldDrop(503));
    }

    @Test
    public void networkFailuresWithNoStatusAreRetried() {
        assertTrue(HttpStatusPolicy.isRetryable(0));
        assertTrue(HttpStatusPolicy.isRetryable(-1));
    }

    @Test
    public void rejectedCredentialsAreDropped() {
        // A key the gateway refuses will still be refused in ten minutes.
        assertTrue(HttpStatusPolicy.shouldDrop(401));
        assertTrue(HttpStatusPolicy.shouldDrop(403));
        assertFalse(HttpStatusPolicy.isRetryable(401));
        assertFalse(HttpStatusPolicy.isRetryable(403));
    }

    @Test
    public void malformedRequestsAreDropped() {
        // Re-posting an identical malformed body cannot start succeeding.
        assertTrue(HttpStatusPolicy.shouldDrop(400));
        assertTrue(HttpStatusPolicy.shouldDrop(422));
        assertFalse(HttpStatusPolicy.isRetryable(400));
        assertFalse(HttpStatusPolicy.isRetryable(422));
    }

    @Test
    public void analyticsCapRejectionIsDroppedNotRetried() {
        // push-source-service throws 402 analytics_limit_reached from DataItemService and
        // HttpDataAdapter when a project is over its analytics cap. That service's own
        // comment states it must not be treated as a transient failure and retried.
        //
        // This was the gap: 402 is the only 4xx the platform uses for a business condition
        // rather than a client mistake, so it was absent from the drop list and fell into
        // the retry branch. A capped customer had every batch retried every ten minutes and
        // never deleted, which parks it at the queue head and stops all delivery behind it.
        assertTrue(HttpStatusPolicy.shouldDrop(402));
        assertFalse(HttpStatusPolicy.isRetryable(402));
    }

    @Test
    public void successIsNeitherRetriedNorDropped() {
        assertFalse(HttpStatusPolicy.isRetryable(200));
        assertFalse(HttpStatusPolicy.shouldDrop(200));
        assertFalse(HttpStatusPolicy.isRetryable(204));
        assertFalse(HttpStatusPolicy.shouldDrop(204));
    }

    /**
     * Every failure must be routed to exactly one of drop or retry.
     *
     * <p>This used to assert only {@code !(drop && retry)}. That is unfalsifiable: shouldDrop is
     * defined as {@code status >= 300 && !isRetryable(status)}, so the conjunction is
     * {@code A && !A && B} — false by construction for any isRetryable whatsoever, including one
     * that returns a constant. An adversarial audit caught it, and replacing isRetryable's body
     * with {@code return false} confirmed it: three sibling tests went red and this one did not
     * notice.
     *
     * <p>The half that carries the weight is the other direction — neither-drop-nor-retry is the
     * wedging case, where a batch is retried forever and blocks every event behind it.
     */
    @Test
    public void everyFailureIsRoutedToExactlyOneOfDropOrRetry() {
        for (int status : new int[] {400, 401, 403, 404, 408, 409, 413, 422, 429, 500, 502, 503}) {
            boolean drop = HttpStatusPolicy.shouldDrop(status);
            boolean retry = HttpStatusPolicy.isRetryable(status);

            assertFalse("status " + status + " must not be both droppable and retryable", drop && retry);
            assertTrue(
                    "status " + status + " is neither dropped nor retryable, which wedges the queue",
                    drop || retry);
        }
    }
    /**
     * The class is a namespace for two static decisions and holds no state, so an instance of it
     * would be meaningless. The private constructor enforces that.
     *
     * <p>It is also the last two uncovered lines in the class, and PIT's line-coverage threshold
     * counts them — a deliberately-unreachable constructor dragged the measurement to 50% on a
     * four-line class. Calling it reflectively is the standard idiom, and it makes the threshold
     * measure the decisions rather than an artifact, without lowering the bar to accommodate one.
     */
    @Test
    public void thePolicyCannotBeInstantiated() throws Exception {
        final java.lang.reflect.Constructor<HttpStatusPolicy> constructor =
                HttpStatusPolicy.class.getDeclaredConstructor();

        assertTrue(
                "a stateless policy must not be instantiable",
                java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()));

        // The instance is created only so the constructor body is executed — PIT's line-coverage
        // threshold counts it, and a deliberately-unreachable constructor otherwise drags a
        // four-line class to 50%. Deliberately NOT asserted on: `assertNotNull` on the result of a
        // successful `newInstance()` asserts a JVM guarantee, not anything about this class, and no
        // production change could ever make that assertion alone fail.
        constructor.setAccessible(true);
        constructor.newInstance();
    }
    /**
     * The lower boundary of "is this an error at all", found by mutation testing rather than by
     * reading: PIT flipped {@code status >= 300} to {@code status > 300} and no test noticed, which
     * means nothing pinned the behaviour of exactly 300.
     *
     * <p>300 must drop. {@code HttpService} does not follow redirects, so a 3xx can never succeed as
     * sent, and the alternative — falling through to neither dropped nor retried — is the wedging
     * case this class was written to remove.
     */
    @Test
    public void threeHundredExactlyIsDroppedRatherThanIgnored() {
        assertTrue("300 is not a success and cannot succeed as sent", HttpStatusPolicy.shouldDrop(300));
        assertFalse(HttpStatusPolicy.isRetryable(300));

        // The other side of the boundary: 299 is still a success and must be left alone.
        assertFalse("299 is a 2xx and must not be dropped", HttpStatusPolicy.shouldDrop(299));
    }

    /** Every redirect status is droppable, since none of them is followed. */
    @Test
    public void everyRedirectIsDropped() {
        for (int status = 300; status <= 308; status++) {
            assertTrue(
                    "status " + status + " cannot succeed as sent and must not wedge the queue",
                    HttpStatusPolicy.shouldDrop(status));
        }
    }
    /**
     * The upper edge of the retryable 5xx band, found by mutation testing rather than by reading:
     * PIT flipped {@code status <= 599} to {@code status < 599} and survived, so nothing pinned 599
     * itself.
     *
     * <p>599 is a real server error and must be retried; 600 is not a valid HTTP status at all and
     * must be dropped rather than retried forever. Both sides are asserted so the band cannot drift
     * in either direction.
     */
    @Test
    public void theRetryableServerErrorBandStopsAt599() {
        assertTrue("599 is a server error and is transient", HttpStatusPolicy.isRetryable(599));
        assertFalse("599 is retryable, so it must not also be dropped", HttpStatusPolicy.shouldDrop(599));

        assertFalse("600 is not a valid HTTP status and must not be retried", HttpStatusPolicy.isRetryable(600));
        assertTrue(
                "600 cannot succeed as sent, so it must be dropped rather than wedge the queue",
                HttpStatusPolicy.shouldDrop(600));

        // The lower edge of the band, for the same reason.
        assertTrue("500 is the first server error", HttpStatusPolicy.isRetryable(500));
        assertFalse("499 is a client error, not transient", HttpStatusPolicy.isRetryable(499));
    }
}
