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
 * {@code deleteEvents = false}, so every 4xx is requeued with backoff forever. For a request the
 * server will never accept (400, 413, 422) that wedges the queue head, so those are dropped. For a
 * failure the platform fixes on its side (401, 402, 403, 404, 408, 429, 5xx) the batch is kept:
 * INT-5897 was these being dropped after one attempt, so events sent during an auth or routing
 * outage were gone by the time the server recovered.
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
    public void rejectedCredentialsAreRetriedNotDropped() {
        // INT-5897. A 401/403 is a key that is rotated, re-enabled or still propagating. The
        // events are valid, so they must still be queued when the key works again. Dropping here
        // deleted every batch sent during the outage after a single attempt.
        assertTrue(HttpStatusPolicy.isRetryable(401));
        assertTrue(HttpStatusPolicy.isRetryable(403));
        assertFalse(HttpStatusPolicy.shouldDrop(401));
        assertFalse(HttpStatusPolicy.shouldDrop(403));
    }

    @Test
    public void analyticsCapRejectionIsRetriedNotDropped() {
        // push-source-service answers 402 analytics_limit_reached while a project is over its
        // cap. The cap is lifted by an upgrade or a new period, and the events are still valid
        // then, so the batch is kept and retried with backoff rather than discarded.
        assertTrue(HttpStatusPolicy.isRetryable(402));
        assertFalse(HttpStatusPolicy.shouldDrop(402));
    }

    @Test
    public void aMissingRouteIsRetriedNotDropped() {
        // A 404 from the gateway during a deploy, or before a project/source is provisioned,
        // recovers server-side. Dropping it lost every event sent while the route was missing.
        assertTrue(HttpStatusPolicy.isRetryable(404));
        assertFalse(HttpStatusPolicy.shouldDrop(404));
    }

    @Test
    public void requestTimeoutIsRetried() {
        assertTrue(HttpStatusPolicy.isRetryable(408));
        assertFalse(HttpStatusPolicy.shouldDrop(408));
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
    public void payloadTooLargeIsDropped() {
        // The queue does not split batches, so the same batch is the same size on every retry.
        assertTrue(HttpStatusPolicy.shouldDrop(413));
        assertFalse(HttpStatusPolicy.isRetryable(413));
    }

    @Test
    public void unrecognisedClientErrorsAreDropped() {
        // The fail-safe default stays: a 4xx nobody listed describes the request, and keeping it
        // would park it at the queue head.
        for (int status : new int[] {405, 409, 410, 415, 418, 451}) {
            assertTrue("status " + status + " must be dropped", HttpStatusPolicy.shouldDrop(status));
            assertFalse("status " + status + " must not be retried", HttpStatusPolicy.isRetryable(status));
        }
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
        for (int status : new int[] {400, 401, 402, 403, 404, 408, 409, 413, 422, 429, 500, 502, 503}) {
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
