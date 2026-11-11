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
 * Retry-versus-drop for a batch, after ruling R-retry as amended (CONTRACT-R2 S1, INT-7436).
 *
 * <ul>
 *   <li><b>Resent once:</b> 503 and 429. {@code HttpService} waits the Retry-After (clamped to
 *       [1, 60] s, 1 s when absent) and sends one more time, in the same call.
 *   <li><b>Dropped:</b> every status at 300 or above that is still the answer after that, since
 *       a status reaches delivery only once the transport is done with it. Re-posting cannot
 *       change a 4xx, and a 5xx resent later risks duplicating a batch the server took.
 *   <li><b>Kept:</b> only a request that never reached the server ({@link NotSentException}).
 *       That is decided from the exception, not from a status, so no status keeps a batch.
 * </ul>
 *
 * <p>The vendored taxonomy kept 403, 404, 408, 429 and every 5xx at the queue head with
 * exponential backoff (INT-5897). It is gone for events. Consent kept it on purpose (a withdrawal
 * must reach the server, plan2/android.md decision (c)), so it lives on as
 * {@link #isConsentRetryable}, which the consent drain alone reads.
 */
/* package */ final class HttpStatusPolicy {

    /** True when the batch can never succeed as sent and must be removed from the queue. */
    /* package */ static boolean shouldDrop(int status) {
        // 3xx included: HttpService does not follow redirects, so a redirect cannot succeed either.
        return status >= 300;
    }

    /** The statuses the transport resends once, after the server's Retry-After. */
    /* package */ static boolean isResentOnce(int status) {
        return status == 503 || status == 429;
    }

    /**
     * The consent drain's taxonomy, frozen as it was before INT-7436: 403, 404, 408, 429, every
     * 5xx up to 599, and no status at all are kept and retried under {@code ConsentRetryPolicy}'s
     * strike count; anything else is a rejection.
     */
    /* package */ static boolean isConsentRetryable(int status) {
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
