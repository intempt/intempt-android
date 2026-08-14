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
 * <p>Mixpanel has no equivalent of this class, and the reason is worth recording
 * precisely, because it is the opposite of what a quick read suggests.
 *
 * <p>{@code HttpService} throws {@link RemoteService.ClientErrorException} for any 4xx and
 * deliberately skips backup-host failover, on the grounds that a different host will not
 * fix a bad request. But {@code ClientErrorException extends IOException}, and
 * {@code DeliveryMessages.sendData} catches {@code IOException} with
 * {@code deleteEvents = false} — so every 4xx is requeued with exponential backoff.
 *
 * <p>Two consequences follow:
 *
 * <ul>
 *   <li><b>429 is already handled correctly.</b> Intempt's gateway signals backpressure
 *       with 429 plus {@code X-RateLimit-*} headers and no {@code Retry-After}, and the
 *       inherited behaviour — requeue, back off, retry — is exactly right.
 *   <li><b>401, 403, 400 and 422 are handled wrongly.</b> None of them can succeed on
 *       retry: a rejected API key stays rejected, a malformed body stays malformed. The
 *       inherited code retries them every ten minutes for the lifetime of the process,
 *       and because the batch is never deleted it sits at the head of the queue and
 *       blocks every event behind it. One bad batch stops all delivery, permanently.
 * </ul>
 *
 * <p>So this class exists to drop what cannot succeed, not to rescue 429.
 */
/* package */ final class HttpStatusPolicy {

    /**
     * True when the batch can never succeed as sent and must be removed from the queue.
     *
     * <p>Dropping loses those events, which is the lesser harm: retaining them wedges the
     * queue head and loses every subsequent event too.
     */
    /* package */ static boolean shouldDrop(int status) {
        // Everything at 300 or above that is not explicitly retryable. This default is
        // inverted on purpose, and it used to be the other way round.
        //
        // The old version enumerated 400/401/402/403/422 as droppable and let everything
        // else fall through to retry-forever. An adversarial sweep of 400..451 showed 46
        // statuses taking that path, and because a retried batch is never deleted it parks
        // at the queue head and blocks every event behind it. Two examples of what that
        // cost:
        //
        //   404  a typo in org, project or sourceId in intempt-config.json. Retrying cannot
        //        fix a wrong URL, so one bad config silently discarded a customer's entire
        //        event stream, forever, with the queue looking healthy.
        //   413  payload too large. The same batch is the same size on every retry.
        //
        // A client error means the request was wrong. Wrong requests do not become right by
        // being repeated, so the safe default for an unrecognised one is to lose that batch
        // and keep delivering everything after it. Only 408 and 429 are transient, and both
        // are listed as retryable below.
        //
        // 3xx is included: HttpService does not follow redirects, so a redirect can never
        // succeed as sent either.
        return status >= 300 && !isRetryable(status);
    }

    /**
     * True for failures a later attempt can plausibly succeed at.
     *
     * <p>This is the allowlist, and it is deliberately short. Anything not named here that
     * is an error gets dropped rather than retried — see {@link #shouldDrop(int)} for why
     * that direction is the safe one.
     */
    /* package */ static boolean isRetryable(int status) {
        // 429: the gateway's backpressure signal, with X-RateLimit-* and no Retry-After.
        //      Requeue and back off is exactly right, and Mixpanel's inherited behaviour.
        // 408: request timeout. Genuinely transient, and it was NOT retried before this
        //      change — it fell into the wedging default instead, which is the worst of
        //      both outcomes: never retried, never dropped.
        // 5xx: server fault, transient by definition. Bounded at 599, not open-ended.
        //      `status >= 500` swept in 600, 999 and Integer.MAX_VALUE, which disagreed with
        //      HttpService's own classification: it treats 5xx as MIN/MAX_UNAVAILABLE_HTTP_RESPONSE_CODE
        //      (500..599) and sends anything above 599 down the ClientErrorException path as
        //      permanent. So the transport called such a status permanent while this policy called
        //      it transient, and which one won depended on which code read it first. A status above
        //      599 is not a valid HTTP status at all; retrying it forever is the wedging behaviour
        //      this class exists to prevent. Found by a hostile-input sweep that asserted on every
        //      value it generated instead of discarding the results.
        // <=0: no usable status at all, ie a network failure or a timeout with no response.
        return status == 408
                || status == 429
                || (status >= 500 && status <= 599)
                || status <= 0;
    }

    private HttpStatusPolicy() {
    }
}
