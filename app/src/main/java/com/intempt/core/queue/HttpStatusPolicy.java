/*
 * Copyright (c) 2026 Intempt Technologies
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Written for Intempt rather than derived from mixpanel-android, but it lives in the vendored
 * package and its constants and behaviour are taken from Mixpanel's MPConfig and MPDbAdapter,
 * so it is licensed under the same terms and recorded in NOTICE.
 */
package com.intempt.core.queue;

/**
 * Decides whether a failed batch should be retried or dropped.
 *
 * <p>Mixpanel has no equivalent of this class. {@code HttpService} throws
 * {@link RemoteService.ClientErrorException} for any 4xx, and {@code DeliveryMessages.sendData}
 * consults this policy to decide whether that batch is deleted or kept and retried with
 * exponential backoff ({@link DeliveryRetryPolicy#nextRetryDelay}).
 *
 * <p>The split is between statuses that describe <b>the request</b> and statuses that describe
 * <b>the server or the integration</b>:
 *
 * <ul>
 *   <li><b>Retried:</b> 401, 402, 403, 404, 408, 429, every 5xx, and no status at all (network
 *       failure). The body is valid; what failed is a credential, a plan limit, a route or the
 *       server's availability, and each of those is fixed on the platform side without the SDK
 *       resending anything different. INT-5897: these used to be dropped after one attempt, so a
 *       gateway that answered 401 or 404 during a deploy or a key rotation deleted every batch
 *       the app tried to send, and once the server recovered there was nothing left to flush.
 *   <li><b>Dropped:</b> everything else at 300 or above — 400, 413, 422, redirects, unrecognised
 *       4xx, and anything above 599. Re-posting the identical body cannot change the answer.
 * </ul>
 *
 * <p>A retried batch parks at the queue head, so the queue is bounded by the limits that already
 * apply to every row: {@link QueueConfig#getDataExpiration()} sweeps rows older than the
 * expiration window, and {@link QueueConfig#getMaximumDatabaseLimit()} caps the file on disk.
 * Backoff is capped at {@link DeliveryRetryPolicy#MAX_DELAY_MS}, so a misconfigured app costs one
 * request every ten minutes, not a hot loop.
 */
/* package */ final class HttpStatusPolicy {

    /**
     * True when the batch can never succeed as sent and must be removed from the queue.
     */
    /* package */ static boolean shouldDrop(int status) {
        // Everything at 300 or above that is not explicitly retryable. The default stays
        // drop-on-unknown: an unrecognised client error means the request itself was wrong, and
        // a wrong request does not become right by being repeated. Keeping it would park it at
        // the queue head until the age sweep removes it.
        //
        // 3xx is included: HttpService does not follow redirects, so a redirect can never
        // succeed as sent either.
        return status >= 300 && !isRetryable(status);
    }

    /**
     * True for failures a later attempt can plausibly succeed at, without the SDK changing what it
     * sends.
     */
    /* package */ static boolean isRetryable(int status) {
        // 401/403: a rejected or not-yet-propagated credential. The events are valid; the key is
        //          fixed (rotated, re-enabled, propagated) on the platform side, and the batch
        //          must still be there when it is. Dropping here was total silent loss for every
        //          event sent during the outage.
        // 402: analytics_limit_reached. Lifted when the plan is upgraded or the period rolls
        //      over; the events are still valid then.
        // 404: a gateway route missing during a deploy, or a project/source not yet provisioned.
        //      A config typo also lands here; it is bounded by the age sweep and costs one
        //      request per backoff period, which is cheaper than discarding a customer's stream
        //      every time a route flaps.
        // 408: request timeout. Transient.
        // 429: the gateway's backpressure signal. Requeue and back off (Retry-After honoured).
        // 5xx: server fault, transient by definition. Bounded at 599, not open-ended: a status
        //      above 599 is not valid HTTP and HttpService sends it down the ClientErrorException
        //      path as permanent, so the two classifications agree.
        // <=0: no usable status at all, ie a network failure or a timeout with no response.
        return status == 401
                || status == 402
                || status == 403
                || status == 404
                || status == 408
                || status == 429
                || (status >= 500 && status <= 599)
                || status <= 0;
    }

    private HttpStatusPolicy() {
    }
}
