package com.intempt.core.queue;

import static org.junit.Assert.assertFalse;
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
    public void successIsNeitherRetriedNorDropped() {
        assertFalse(HttpStatusPolicy.isRetryable(200));
        assertFalse(HttpStatusPolicy.shouldDrop(200));
        assertFalse(HttpStatusPolicy.isRetryable(204));
        assertFalse(HttpStatusPolicy.shouldDrop(204));
    }

    @Test
    public void dropAndRetryAreMutuallyExclusive() {
        // A status routed to both, or to neither when it is a failure, means the caller's
        // if/else in sendData would silently pick one and hide the other.
        for (int status : new int[] {400, 401, 403, 404, 409, 422, 429, 500, 502, 503}) {
            assertFalse(
                    "status " + status + " must not be both droppable and retryable",
                    HttpStatusPolicy.shouldDrop(status) && HttpStatusPolicy.isRetryable(status));
        }
    }
}
