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
 *   <li><b>Retried:</b> 403, 404, 408, 429, every 5xx, and no status at all (network failure).
 *       INT-5897 moved these off the drop path so a gateway that answered 403 or 404 during a
 *       deploy did not delete every batch the app tried to send. Whether they stay here is
 *       K1/INT-7436's question under ruling R-retry.
 *   <li><b>Dropped:</b> everything else at 300 or above — 400, 401, 402, 413, 422, redirects,
 *       unrecognised 4xx, and anything above 599. Re-posting the identical body cannot change the
 *       answer. 401 and 402 are here by ruling R-retry (Beso D9, INT-7438): the backend will never
 *       accept an event it answered 400, 401 or 402 for, so the batch is dropped on the first one.
 *       This reverses INT-5897 for those two, and with it the loss of events sent during a key
 *       rotation or while a project is over its analytics cap.
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
        // 401 and 402 are absent on purpose: ruling R-retry (INT-7438), see the class javadoc.
        // 403: a forbidden or not-yet-propagated credential, kept by INT-5897 pending K1.
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
        return status == 403
                || status == 404
                || status == 408
                || status == 429
                || (status >= 500 && status <= 599)
                || status <= 0;
    }

    private HttpStatusPolicy() {
    }
}
